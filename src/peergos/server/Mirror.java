package peergos.server;

import peergos.server.corenode.*;
import peergos.server.login.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.logging.*;

public class Mirror {

    public static void mirrorNode(Multihash nodeId,
                                  BatWithId mirrorBat,
                                  CoreNode core,
                                  MutablePointers p2pPointers,
                                  DeletableContentAddressedStorage storage,
                                  JdbcIpnsAndSocial targetPointers,
                                  TransactionStore transactions,
                                  Hasher hasher) {
        Logging.LOG().log(Level.INFO, "Mirroring data for node " + nodeId);
        List<String> allUsers = core.getUsernames("").join();
        int userCount = 0;
        for (String username : allUsers) {
            List<UserPublicKeyLink> chain = core.getChain(username).join();
            if (chain.get(chain.size() - 1).claim.storageProviders.contains(nodeId)) {
                try {
                    mirrorUser(username, Optional.empty(), Optional.of(mirrorBat), core, p2pPointers, null,
                            storage, targetPointers, null, transactions, hasher);
                    userCount++;
                } catch (Exception e) {
                    Logging.LOG().log(Level.WARNING, "Couldn't mirror user: " + username, e);
                }
            }
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for node " + nodeId + ", with " + userCount + " users.");
    }

    /**
     *
     * @param username
     * @param core
     * @param p2pPointers
     * @param storage
     * @param targetPointers
     * @param transactions
     * @param hasher
     * @return The version mirrored
     */
    public static Map<PublicKeyHash, byte[]> mirrorUser(String username,
                                                        Optional<SigningKeyPair> loginAuth,
                                                        Optional<BatWithId> mirrorBat,
                                                        CoreNode core,
                                                        MutablePointers p2pPointers,
                                                        Account p2pAccount,
                                                        DeletableContentAddressedStorage storage,
                                                        JdbcIpnsAndSocial targetPointers,
                                                        JdbcAccount targetAccount,
                                                        TransactionStore transactions,
                                                        Hasher hasher) {
        Logging.LOG().log(Level.INFO, "Mirroring data for " + username);
        Optional<PublicKeyHash> identity = core.getPublicKeyHash(username).join();
        if (! identity.isPresent())
            return Collections.emptyMap();
        PublicKeyHash owner = identity.get();
        Map<PublicKeyHash, byte[]> versions = new HashMap<>();
        Set<PublicKeyHash> ownedKeys = WriterData.getOwnedKeysRecursive(owner, owner, p2pPointers, storage, hasher).join();
        for (PublicKeyHash ownedKey : ownedKeys) {
            Optional<byte[]> version = mirrorMutableSubspace(owner, ownedKey, mirrorBat, p2pPointers, storage,
                    targetPointers, transactions, hasher);
            if (version.isPresent())
                versions.put(ownedKey, version.get());
        }
        if (loginAuth.isPresent()) {
            SigningKeyPair login = loginAuth.get();
            UserStaticData entryData = p2pAccount.getLoginData(username, login.publicSigningKey, TimeLimitedClient.signNow(login.secretSigningKey)).join();
            targetAccount.setLoginData(new LoginData(username, entryData, login.publicSigningKey, Optional.empty())).join();
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for " + username);
        return versions;
    }

    /**
     *
     * @param owner
     * @param writer
     * @param p2pPointers
     * @param storage
     * @param targetPointers
     * @return the version mirrored
     */
    public static Optional<byte[]> mirrorMutableSubspace(PublicKeyHash owner,
                                                         PublicKeyHash writer,
                                                         Optional<BatWithId> mirrorBat,
                                                         MutablePointers p2pPointers,
                                                         DeletableContentAddressedStorage storage,
                                                         JdbcIpnsAndSocial targetPointers,
                                                         TransactionStore transactions,
                                                         Hasher hasher) {
        Optional<byte[]> updated = p2pPointers.getPointer(owner, writer).join();
        if (! updated.isPresent()) {
            Logging.LOG().log(Level.WARNING, "Skipping unretrievable mutable pointer for: " + writer);
            return updated;
        }

        mirrorMerkleTree(owner, writer, updated.get(), mirrorBat, storage, targetPointers, transactions, hasher);
        return updated;
    }

    public static void mirrorMerkleTree(PublicKeyHash owner,
                                        PublicKeyHash writer,
                                        byte[] newPointer,
                                        Optional<BatWithId> mirrorBat,
                                        DeletableContentAddressedStorage storage,
                                        JdbcIpnsAndSocial targetPointers,
                                        TransactionStore transactions,
                                        Hasher hasher) {
        Optional<byte[]> existing = targetPointers.getPointer(writer).join();
        // First pin the new root, then commit updated pointer
        MaybeMultihash existingTarget = existing.isPresent() ?
                MutablePointers.parsePointerTarget(existing.get(), writer, storage).join() :
                MaybeMultihash.empty();
        MaybeMultihash updatedTarget = MutablePointers.parsePointerTarget(newPointer, writer, storage).join();
        // use a mirror call to distinguish from normal pin calls
        TransactionId tid = transactions.startTransaction(owner);
        try {
            storage.mirror(owner, existingTarget.toOptional().map(c -> (Cid)c),
                    updatedTarget.toOptional().map(c -> (Cid)c),
                    mirrorBat, storage.id().join(), tid, hasher);
            targetPointers.setPointer(writer, existing, newPointer).join();
        } finally {
            transactions.closeTransaction(owner, tid);
        }
    }
}
