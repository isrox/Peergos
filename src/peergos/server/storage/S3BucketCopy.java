package peergos.server.storage;

import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

public class S3BucketCopy {

    private static final Logger LOG = Logger.getGlobal();

    private static void applyToAllInRange(Consumer<S3AdminRequests.ObjectMetadata> processor,
                                          String startPrefix,
                                          Optional<String> endPrefix,
                                          S3Config config,
                                          AtomicLong counter,
                                          Hasher h) {
        try {
            Optional<String> continuationToken = Optional.empty();
            S3AdminRequests.ListObjectsReply result;
            do {
                result = S3AdminRequests.listObjects(startPrefix, 1_000, continuationToken,
                        ZonedDateTime.now(), config.getHost(), config.region, config.accessKey, config.secretKey, url -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, S3AdminRequests.builder::get, h);

                for (S3AdminRequests.ObjectMetadata objectSummary : result.objects) {
                    if (objectSummary.key.endsWith("/")) {
                        LOG.fine(" - " + objectSummary.key + "  " + "(directory)");
                        continue;
                    }
                    if (endPrefix.isPresent() && objectSummary.key.compareTo(endPrefix.get()) >= 0)
                        return;
                    processor.accept(objectSummary);
                }
                long done = counter.addAndGet(result.objects.size());
                if ((done / 1000) % 10 == 0)
                    System.out.println("Objects processed: " + done);
                LOG.log(Level.FINE, "Next Continuation Token : " + result.continuationToken);
                continuationToken = result.continuationToken;
            } while (result.isTruncated);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private static Set<String> getFilenames(S3Config config, Hasher h) {
        Set<String> results = new HashSet<>();
        applyToAllInRange(obj -> results.add(obj.key), "", Optional.empty(), config, new AtomicLong(0), h);
        return results;
    }

    private static void copyObject(String key,
                                   String sourceBucket,
                                   S3Config config,
                                   Hasher h) {
        PresignedUrl copyUrl = S3Request.preSignCopy(sourceBucket, key, key, S3AdminRequests.asAwsDate(ZonedDateTime.now()), config.getHost(),
                Collections.emptyMap(), config.region, config.accessKey, config.secretKey, h).join();
        try {
            System.out.println("Copying s3://"+sourceBucket + "/" + key + " to s3://" + config.bucket);
            String res = new String(HttpUtil.put(copyUrl, new byte[0]));
            if (! res.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><CopyObjectResult") || !res.contains("</LastModified><ETag>"))
                throw new IllegalStateException(res);
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }

    private static void copyRange(String startPrefix,
                                  Optional<String> endPrefix,
                                  S3Config sourceConfig,
                                  S3Config destConfig,
                                  AtomicLong counter,
                                  AtomicLong copyCounter,
                                  int parallelism,
                                  Hasher h) {
        System.out.println("Listing destination bucket...");
        Set<String> targetKeys = getFilenames(destConfig, h);
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        System.out.println("Copying objects...");
        applyToAllInRange(obj -> {
            if (!targetKeys.contains(obj.key)) {
                while (pool.getQueuedSubmissionCount() > 100)
                    try {Thread.sleep(100);} catch (InterruptedException e) {}
                copyCounter.incrementAndGet();
                pool.submit(() -> copyObject(obj.key, sourceConfig.bucket, destConfig, h));
            }
        }, startPrefix, endPrefix, sourceConfig, counter, h);
        while (! pool.isQuiescent())
            try {Thread.sleep(100);} catch (InterruptedException e) {}
        System.out.println("Objects copied: " + copyCounter.get());
    }

    public static void main(String[] args) {
        Args a = Args.parse(args);
        S3Config destConfig = S3Config.build(a);
        String sourceBucket = a.getArg("source-bucket");
        S3Config sourceConfig = new S3Config(destConfig.path, sourceBucket, destConfig.region, destConfig.accessKey,
                destConfig.secretKey, destConfig.regionEndpoint);

        String startPrefix = "";
        Optional<String> endPrefix = Optional.empty();

        System.out.println("Copying S3 bucket " + sourceBucket + " to " + destConfig.bucket);
        copyRange(startPrefix, endPrefix, sourceConfig, destConfig, new AtomicLong(0),
                new AtomicLong(0), a.getInt("parallelism"), Main.initCrypto().hasher);
    }
}
