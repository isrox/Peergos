package peergos.shared.user.fs;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class FileUploader implements AutoCloseable {
	private static final Logger LOG = Logger.getGlobal();

    private final String name;
    private final long offset, length;
    private final FileProperties props;
    private final SymmetricKey baseKey;
    private final SymmetricKey dataKey;
    private final long nchunks;
    private final Location parentLocation;
    private final Optional<Bat> parentBat;
    private final SymmetricKey parentparentKey;
    private final ProgressConsumer<Long> monitor;
    private final AsyncReader reader; // resettable input stream
    private final byte[] firstLocation;
    private final Optional<Bat> firstBat;

    @JsConstructor
    public FileUploader(String name, String mimeType, AsyncReader fileData,
                        int offsetHi, int offsetLow, int lengthHi, int lengthLow,
                        SymmetricKey baseKey,
                        SymmetricKey dataKey,
                        Location parentLocation,
                        Optional<Bat> parentBat,
                        SymmetricKey parentparentKey,
                        ProgressConsumer<Long> monitor,
                        FileProperties fileProperties,
                        byte[] firstLocation,
                        Optional<Bat> firstBat) {
        long length = (lengthLow & 0xFFFFFFFFL) + ((lengthHi & 0xFFFFFFFFL) << 32);
        if (fileProperties == null)
            this.props = new FileProperties(name, false, false, mimeType, length, LocalDateTime.now(),
                    false, Optional.empty(), Optional.empty());
        else
            this.props = fileProperties;
        if (baseKey == null) baseKey = SymmetricKey.random();

        long offset = (offsetLow & 0xFFFFFFFFL) + ((offsetHi & 0xFFFFFFFFL) << 32);

        // Process and upload chunk by chunk to avoid running out of RAM, in reverse order to build linked list
        this.nchunks = length > 0 ? (length + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE : 1;
        this.name = name;
        this.offset = offset;
        this.length = length;
        this.reader = fileData;
        this.baseKey = baseKey;
        this.dataKey = dataKey;
        this.parentLocation = parentLocation;
        this.parentBat = parentBat;
        this.parentparentKey = parentparentKey;
        this.monitor = monitor;
        this.firstLocation = firstLocation;
        this.firstBat = firstBat;
    }

    public FileUploader(String name, String mimeType, AsyncReader fileData, long offset, long length,
                        SymmetricKey baseKey, SymmetricKey dataKey, Location parentLocation, Optional<Bat> parentBat,
                        SymmetricKey parentparentKey, ProgressConsumer<Long> monitor, FileProperties fileProperties,
                        byte[] firstLocation, Optional<Bat> firstBat) {
        this(name, mimeType, fileData, (int)(offset >> 32), (int) offset, (int) (length >> 32), (int) length,
                baseKey, dataKey, parentLocation, parentBat, parentparentKey, monitor, fileProperties, firstLocation, firstBat);
    }

    public CompletableFuture<Snapshot> uploadChunk(Snapshot current,
                                                   Committer committer,
                                                   NetworkAccess network,
                                                   PublicKeyHash owner,
                                                   SigningPrivateKeyAndPublicHash writer,
                                                   long chunkIndex,
                                                   MaybeMultihash ourExistingHash,
                                                   ProgressConsumer<Long> monitor,
                                                   Optional<BatId> mirrorBat,
                                                   SafeRandom random,
                                                   Hasher hasher) {
        LOG.info("uploading chunk: "+chunkIndex + " of "+name);
        long position = chunkIndex * Chunk.MAX_SIZE;

        long fileLength = length;
        boolean isLastChunk = fileLength < position + Chunk.MAX_SIZE;
        int length =  isLastChunk ? (int)(fileLength -  position) : Chunk.MAX_SIZE;
        byte[] data = new byte[length];
        return reader.readIntoArray(data, 0, data.length).thenCompose(b -> {
            byte[] nonce = baseKey.createNonce();
            return FileProperties.calculateMapKey(props.streamSecret.get(), firstLocation, firstBat,
                    chunkIndex * Chunk.MAX_SIZE, hasher)
                    .thenCompose(mapKeyAndBat -> {
                        Chunk chunk = new Chunk(data, dataKey, mapKeyAndBat.left, nonce);
                        LocatedChunk locatedChunk = new LocatedChunk(new Location(owner, writer.publicKeyHash, chunk.mapKey()), mapKeyAndBat.right, ourExistingHash, chunk);
                        return FileProperties.calculateNextMapKey(props.streamSecret.get(), mapKeyAndBat.left, mapKeyAndBat.right, hasher)
                                .thenCompose(nextMapKeyAndBat -> {
                                    Location nextLocation = new Location(owner, writer.publicKeyHash, nextMapKeyAndBat.left);
                                    return uploadChunk(current, committer, writer, props, parentLocation, parentBat, parentparentKey, baseKey, locatedChunk,
                                            nextLocation, nextMapKeyAndBat.right, Optional.empty(), mirrorBat, random, hasher, network, monitor);
                                });
                    });
        });
    }

    public CompletableFuture<Snapshot> upload(Snapshot current,
                                              Committer committer,
                                              NetworkAccess network,
                                              PublicKeyHash owner,
                                              SigningPrivateKeyAndPublicHash writer,
                                              Optional<BatId> mirrorBat,
                                              SafeRandom random,
                                              Hasher hasher) {
        long t1 = System.currentTimeMillis();

        List<Integer> input = IntStream.range(0, (int) nchunks).mapToObj(i -> Integer.valueOf(i)).collect(Collectors.toList());
        return Futures.reduceAll(input, current, (cwd, i) -> uploadChunk(cwd, committer, network, owner, writer, i,
                MaybeMultihash.empty(), monitor, mirrorBat, random, hasher), (a, b) -> b)
                .thenApply(x -> {
                    LOG.info("File encryption, upload took: " +(System.currentTimeMillis()-t1) + " mS");
                    return x;
                });
    }

    public static CompletableFuture<Snapshot> uploadChunk(Snapshot current,
                                                          Committer committer,
                                                          SigningPrivateKeyAndPublicHash writer,
                                                          FileProperties props,
                                                          Location parentLocation,
                                                          Optional<Bat> parentBat,
                                                          SymmetricKey parentparentKey,
                                                          SymmetricKey baseKey,
                                                          LocatedChunk chunk,
                                                          Location nextChunkLocation,
                                                          Optional<Bat> nextChunkBat,
                                                          Optional<SymmetricLinkToSigner> writerLink,
                                                          Optional<BatId> mirrorBat,
                                                          SafeRandom random,
                                                          Hasher hasher,
                                                          NetworkAccess network,
                                                          ProgressConsumer<Long> monitor) {
        CappedProgressConsumer progress = new CappedProgressConsumer(monitor, chunk.chunk.length());
        if (! writer.publicKeyHash.equals(chunk.location.writer))
            throw new IllegalStateException("Trying to write a chunk to the wrong signing key space!");
        RelativeCapability nextChunk = RelativeCapability.buildSubsequentChunk(nextChunkLocation.getMapKey(), nextChunkBat, baseKey);
        return CryptreeNode.createFile(chunk.existingHash, chunk.location.writer, baseKey,
                chunk.chunk.key(), props, chunk.chunk.data(), parentLocation, parentBat, parentparentKey, nextChunk,
                chunk.bat, mirrorBat, random, hasher, network.isJavascript())
                .thenCompose(file -> {
                    CryptreeNode metadata = file.left.withWriterLink(baseKey, writerLink);

                    List<Fragment> fragments = file.right.stream()
                            .filter(f -> !f.isInlined())
                            .map(f -> f.fragment)
                            .collect(Collectors.toList());

                    if (fragments.size() < file.right.size() || fragments.isEmpty())
                        progress.accept((long) chunk.chunk.length());
                    LOG.info("Uploading chunk with " + fragments.size() + " fragments\n");
                    return IpfsTransaction.call(chunk.location.owner,
                            tid -> network.uploadFragments(fragments, chunk.location.owner, writer, progress, tid)
                                    .thenCompose(hashes -> network.uploadChunk(current, committer, metadata, chunk.location.owner,
                                            chunk.chunk.mapKey(), writer, tid)),
                            network.dhtClient);
                });
    }

    public void close() {
        reader.close();
    }
}
