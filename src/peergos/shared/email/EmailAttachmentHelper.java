package peergos.shared.email;

import jsinterop.annotations.JsMethod;
import peergos.shared.display.FileRef;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Pair;
import peergos.shared.util.ProgressConsumer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Attachments are stored in: /$username/.apps/email/data/attachments/$year/$month/
 */
public class EmailAttachmentHelper {
    private static final Path attachmentsDir = Paths.get(".apps", "email", "data");

    @JsMethod
    public static CompletableFuture<Pair<String, FileRef>> upload(UserContext context, String username, String directoryPrefix, AsyncReader reader,
                                                                String fileExtension,
                                                                int length,
                                                                ProgressConsumer<Long> monitor) {
        String uuid = UUID.randomUUID().toString() + "." + fileExtension;
        return getOrMkdirToStoreAttachment(context, username, directoryPrefix)
                .thenCompose(p -> p.right.uploadAndReturnFile(uuid, reader, length, false, monitor,
                        context.network, context.crypto)
                        .thenCompose(f ->  reader.reset().thenCompose(r -> context.crypto.hasher.hash(r, length))
                                .thenApply(hash -> new Pair<>(f.getFileProperties().getType(),
                                        new FileRef(p.left.resolve(uuid).toString(), f.readOnlyPointer(), hash)))));
    }

    private static CompletableFuture<Pair<Path, FileWrapper>> getOrMkdirToStoreAttachment(UserContext context, String username, String directoryPrefix) {
        LocalDateTime postTime = LocalDateTime.now();
        Path baseDir = Paths.get(username + "/" + attachmentsDir + "/" + directoryPrefix + "/attachments");
        Path dirFromBase = Paths.get(
                Integer.toString(postTime.getYear()),
                Integer.toString(postTime.getMonthValue()));
        return context.getByPath(baseDir)
                .thenCompose(home -> home.get().getOrMkdirs(dirFromBase, context.network, true, context.crypto)
                .thenApply(dir -> new Pair<>(baseDir.resolve(dirFromBase), dir)));
    }
}
