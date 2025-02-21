package peergos.server.storage;

import peergos.server.storage.auth.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** A local directory implementation of ContentAddressedStorage. Only used for testing.
 *
 */
public class FileContentAddressedStorage implements DeletableContentAddressedStorage {
    private static final Logger LOG = Logging.LOG();
    private static final int CID_V1 = 1;
    private static final int DIRECTORY_DEPTH = 5;
    private final Path root;
    private final TransactionStore transactions;
    private final BlockRequestAuthoriser authoriser;
    private final Hasher hasher;

    public FileContentAddressedStorage(Path root, TransactionStore transactions, BlockRequestAuthoriser authoriser, Hasher hasher) {
        this.root = root;
        this.transactions = transactions;
        this.authoriser = authoriser;
        this.hasher = hasher;
        File rootDir = root.toFile();
        if (!rootDir.exists()) {
            final boolean mkdirs = root.toFile().mkdirs();
            if (!mkdirs)
                throw new IllegalStateException("Unable to create directory " + root);
        }
        if (!rootDir.isDirectory())
            throw new IllegalStateException("File store path must be a directory! " + root);
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return CompletableFuture.completedFuture(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, RAMStorage.hash("FileStorage".getBytes())));
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return CompletableFuture.completedFuture(transactions.startTransaction(owner));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        transactions.closeTransaction(owner, tid);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        if (! hasBlock(root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(root, champKey, bat, hasher);
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        return transactions.getOpenTransactionBlocks();
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return put(owner, writer, signedHashes, blocks, false, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return put(owner, writer, signatures, blocks, true, tid);
    }

    private CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             List<byte[]> signatures,
                                             List<byte[]> blocks,
                                             boolean isRaw,
                                             TransactionId tid) {
        return CompletableFuture.completedFuture(blocks.stream()
                .map(b -> put(b, isRaw, tid, owner))
                .collect(Collectors.toList()));
    }

    private Path getFilePath(Cid h) {
        String name = h.toString();

        int depth = DIRECTORY_DEPTH;
        Path path = Paths.get("");
        for (int i=0; i < depth; i++)
            path = path.resolve(Character.toString(name.charAt(i)));
        // include full name in filename
        path = path.resolve(name);
        return path;
    }

    /**
     * Remove all files stored as part of this FileContentAddressedStorage.
     */
    public void remove() {
        root.toFile().delete();
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, String auth) {
        if (hash.codec == Cid.Codec.Raw)
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return getRaw(hash, auth).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        return get(hash, bat, id().join(), hasher);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return getRaw(hash, bat, id().join(), hasher);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth) {
        return getRaw(hash, auth, true);
    }

    private CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth, boolean doAuth) {
        try {
            if (hash.isIdentity())
                return Futures.of(Optional.of(hash.getHash()));
            Path path = getFilePath(hash);
            File file = root.resolve(path).toFile();
            if (! file.exists()){
                return CompletableFuture.completedFuture(Optional.empty());
            }
            try (DataInputStream din = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                byte[] block = Serialize.readFully(din);
                if (doAuth && ! authoriser.allowRead(hash, block, id().join(), auth).join())
                    return Futures.errored(new IllegalStateException("Unauthorised!"));
                return CompletableFuture.completedFuture(Optional.of(block));
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public boolean hasBlock(Cid hash) {
        Path path = getFilePath(hash);
        File file = root.resolve(path).toFile();
        return file.exists();
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid root, String auth) {
        if (root.codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getRaw(root, auth, false)
                .thenApply(opt -> opt.map(CborObject::fromByteArray))
                .thenApply(opt -> opt
                        .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                        .orElse(Collections.emptyList())
                );
    }

    public Cid put(byte[] data, boolean isRaw, TransactionId tid, PublicKeyHash owner) {
        try {
            Cid cid = new Cid(CID_V1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor,
                    Multihash.Type.sha2_256, RAMStorage.hash(data));
            Path filePath = getFilePath(cid);
            Path target = root.resolve(filePath);
            Path parent = target.getParent();
            File parentDir = parent.toFile();

            if (! parentDir.exists() && ! parentDir.mkdirs())
                throw new IllegalStateException("Couldn't create directory: " + parent);
            for (Path someParent = parent; !someParent.equals(root); someParent = someParent.getParent()) {
                File someParentFile = someParent.toFile();
                if (! someParentFile.canWrite()) {
                    final boolean b = someParentFile.setWritable(true, false);
                    if (!b)
                        throw new IllegalStateException("Could not make " + someParent.toString() + ", ancestor of " + parentDir.toString() + " writable");
                }
            }
            transactions.addBlock(cid, tid, owner);
            File targetFile = target.toFile();
            Path tmp = Files.createTempFile(root, "tmp", "");
            File tmpFile = tmp.toFile();
            Path lockPath = parent.resolve("lock." + filePath.toFile().getName());
            try (RandomAccessFile rw = new RandomAccessFile(lockPath.toFile(), "rw");
                 FileLock lock = rw.getChannel().lock();
                 DataOutputStream dout = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmpFile)))) {

                dout.write(data, 0, data.length);
                boolean setWritableSuccess = tmpFile.setWritable(false, false);
                boolean setReadableSuccess = tmpFile.setReadable(true, false);
                boolean renameSuccess = tmpFile.renameTo(targetFile);
                boolean deleteSuccess = lockPath.toFile().delete();
                boolean lockExists = lockPath.toFile().exists();
                if (!setWritableSuccess)
                    throw new IllegalStateException("Error setting " + tmpFile.getName() + " to writable");
                if (!setReadableSuccess)
                    throw new IllegalStateException("Error setting " + tmpFile.getName() + " to readable");
                if (!renameSuccess)
                    throw new IllegalStateException("Error renaming " + tmpFile.getName() + " to " + targetFile.getName());
                if (!deleteSuccess && lockExists)
                    throw new IllegalStateException("Error deleting " + lockPath.toFile().getName());
            } finally {
                if (tmpFile.exists())
                    tmpFile.delete();
            }
            return cid;
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    protected List<Cid> getFiles() {
        List<Cid> existing = new ArrayList<>();
        getFilesRecursive(root, existing::add);
        return existing;
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash h) {
        Path path = getFilePath((Cid)h);
        File file = root.resolve(path).toFile();
        return CompletableFuture.completedFuture(file.exists() ? Optional.of((int) file.length()) : Optional.empty());
    }

    @Override
    public Stream<Cid> getAllBlockHashes() {
        return getFiles().stream();
    }

    @Override
    public void delete(Multihash h) {
        Path path = getFilePath((Cid)h);
        File file = root.resolve(path).toFile();
        if (file.exists())
            file.delete();
    }

    public Optional<Long> getLastAccessTimeMillis(Cid h) {
        Path path = getFilePath(h);
        File file = root.resolve(path).toFile();
        if (! file.exists())
            return Optional.empty();
        try {
            BasicFileAttributes attrs = Files.readAttributes(root.resolve(path), BasicFileAttributes.class);
            FileTime time = attrs.lastAccessTime();
            return Optional.of(time.toMillis());
        } catch (NoSuchFileException nope) {
            return Optional.empty();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void applyToAll(Consumer<Cid> processor) {
        getFilesRecursive(root, processor);
    }

    private void getFilesRecursive(Path path, Consumer<Cid> accumulator) {
        File pathFile = path.toFile();
        if (pathFile.isFile()) {
            accumulator.accept(Cid.decode(pathFile.getName()));
            return;
        }
        else if (!  pathFile.isDirectory())
            throw new IllegalStateException("Specified path "+ path +" is not a file or directory");

        String[] filenames = pathFile.list();
        if (filenames == null)
            throw new IllegalStateException("Couldn't retrieve children of directory: " + path);
        for (String filename : filenames) {
            Path child = path.resolve(filename);
            if (child.toFile().isDirectory()) {
                getFilesRecursive(child, accumulator);
            } else if (filename.startsWith("Q") || filename.startsWith("z")) { // tolerate non content addressed files in the same space
                try {
                    accumulator.accept(Cid.decode(child.toFile().getName()));
                } catch (IllegalStateException e) {
                    // ignore files who's name isn't a valid multihash
                    LOG.info("Ignoring file "+ child +" since name is not a valid multihash");
                }
            }
        }
    }

    public Set<Cid> retainOnly(Set<Cid> pins) {
        List<Cid> existing = getFiles();
        Set<Cid> removed = new HashSet<>();
        for (Cid h : existing) {
            if (! pins.contains(h)) {
                removed.add(h);
                File file = root.resolve(getFilePath(h)).toFile();
                if (file.exists() && !file.delete())
                    LOG.warning("Could not delete " + file);
                File legacy = root.resolve(h.toString()).toFile();
                if (legacy.exists() && ! legacy.delete())
                    LOG.warning("Could not delete " + legacy);
            }
        }
        return removed;
    }

    public boolean contains(Multihash multihash) {
        Path path = getFilePath((Cid)multihash);
        File file = root.resolve(path).toFile();
        if (! file.exists()) { // for backwards compatibility with existing data
            file = root.resolve(path.getFileName()).toFile();
        }
        return file.exists();
    }

    @Override
    public String toString() {
        return "FileContentAddressedStorage " + root;
    }
}
