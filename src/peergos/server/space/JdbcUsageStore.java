package peergos.server.space;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcUsageStore implements UsageStore {
	private static final Logger LOG = Logging.LOG();

    private Supplier<Connection> conn;
    private final SqlSupplier commands;
    private volatile boolean isClosed;

    public JdbcUsageStore(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
        this.commands = commands;
        init(commands);
    }

    private Connection getConnection() {
        Connection connection = conn.get();
        try {
            connection.setAutoCommit(true);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        if (isClosed)
            return;

        try (Connection conn = getConnection()) {
            commands.createTable(commands.createUsageTablesCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void initialized() {
        // TODO can we remove this method?
    }

    @Override
    public void addUserIfAbsent(String username) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO users (name) VALUES(?)"))) {
            insert.setString(1, username);
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
        int userId = getUserId(username);
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO userusage (user_id, total_bytes, errored) VALUES(?, ?, ?)"))) {
            insert.setInt(1, userId);
            insert.setLong(2, 0);
            insert.setBoolean(3, false);
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void confirmUsage(String username, PublicKeyHash writer, long usageDelta, boolean errored) {
        int userId = getUserId(username);
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(
                "UPDATE userusage SET total_bytes = total_bytes + ?, errored = ? " +
                        "WHERE user_id = ?;");
             PreparedStatement insertPending = conn.prepareStatement(
                     "UPDATE pendingusage SET pending_bytes = ? " +
                             "WHERE writer_id = (SELECT id FROM writers WHERE key_hash = ?);")) {
            insert.setLong(1, usageDelta);
            insert.setBoolean(2, errored);
            insert.setInt(3, userId);

            int count = insert.executeUpdate();
            if (count != 1)
                throw new IllegalStateException("Didn't update one record!");
            insertPending.setLong(1, 0);
            insertPending.setBytes(2, writer.toBytes());
            int count2 = insertPending.executeUpdate();
            if (count2 != 1)
                throw new IllegalStateException("Didn't update one record!");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void addPendingUsage(String username, PublicKeyHash writer, int size) {
        int userId = getUserId(username);
        int writerId = getWriterId(writer);
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO pendingusage (user_id, writer_id, pending_bytes) VALUES(?, ?, ?)"))) {
            insert.setInt(1, userId);
            insert.setInt(2, writerId);
            insert.setInt(3, 0);
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement("UPDATE pendingusage SET pending_bytes = pending_bytes + ? " +
                "WHERE writer_id = ?;")) {
            insert.setLong(1, size);
            insert.setInt(2, writerId);
            int count = insert.executeUpdate();
            if (count != 1)
                throw new IllegalStateException("Didn't update one record!");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public UserUsage getUsage(String username) {
        int userId = getUserId(username);
        try (Connection conn = getConnection();
             PreparedStatement search = conn.prepareStatement("SELECT pu.writer_id, pu.pending_bytes, uu.total_bytes, uu.errored " +
                "FROM userusage uu, pendingusage pu WHERE uu.user_id = pu.user_id AND uu.user_id = ?;")) {
            search.setInt(1, userId);
            ResultSet resultSet = search.executeQuery();
            Map<PublicKeyHash, Long> pending = new HashMap<>();
            long totalBytes = -1;
            boolean errored = false;
            while (resultSet.next()) {
                pending.put(getWriter(resultSet.getInt(1)), resultSet.getLong(2));
                if (totalBytes == -1) {
                    totalBytes = resultSet.getLong(3);
                    errored = resultSet.getBoolean(4);
                }
            }

            return new UserUsage(totalBytes, errored, pending);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private int getUserId(String username) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement("SELECT id FROM users WHERE name = ?;")) {
            insert.setString(1, username);
            ResultSet resultSet = insert.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private int getWriterId(PublicKeyHash writer) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement("SELECT id FROM writers WHERE key_hash = ?;")) {
            insert.setBytes(1, writer.toBytes());
            ResultSet resultSet = insert.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void addWriter(String owner, PublicKeyHash writer) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO writers (key_hash) VALUES(?)"))) {
            insert.setBytes(1, writer.toBytes());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
        int userId = getUserId(owner);
        int writerId = getWriterId(writer);

        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO writerusage (writer_id, user_id, direct_size) VALUES(?, ?, ?)"))) {
            insert.setInt(1, writerId);
            insert.setInt(2, userId);
            insert.setInt(3, 0);
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public Set<PublicKeyHash> getAllWriters() {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement("SELECT key_hash FROM writers;")) {
            Set<PublicKeyHash> res = new HashSet<>();
            ResultSet resultSet = insert.executeQuery();
            while (resultSet.next())
                res.add(PublicKeyHash.decode(resultSet.getBytes(1)));
            return res;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private String getOwner(PublicKeyHash writer) {
        int writerId = getWriterId(writer);
        try (Connection conn = getConnection();
             PreparedStatement search = conn.prepareStatement("SELECT u.name FROM users u, writerusage wu WHERE u.id = wu.user_id AND wu.writer_id = ?;")) {
            search.setInt(1, writerId);
            ResultSet resultSet = search.executeQuery();
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private PublicKeyHash getWriter(int writerId) {
        try (Connection conn = getConnection();
             PreparedStatement search = conn.prepareStatement("SELECT key_hash FROM writers WHERE id = ?;")) {
            search.setInt(1, writerId);
            ResultSet resultSet = search.executeQuery();
            resultSet.next();
            return PublicKeyHash.decode(resultSet.getBytes(1));
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public WriterUsage getUsage(PublicKeyHash writer) {
        String owner = getOwner(writer);
        int writerId = getWriterId(writer);
        Set<PublicKeyHash> owned = new HashSet<>();
        try (Connection conn = getConnection();
             PreparedStatement ownedSearch = conn.prepareStatement("SELECT owned_id FROM ownedkeys WHERE parent_id = ?;");
             PreparedStatement usageSearch = conn.prepareStatement("SELECT target, direct_size FROM writerusage WHERE writer_id = ?;")) {
            ownedSearch.setInt(1, writerId);
            ResultSet ownedRes = ownedSearch.executeQuery();
            while (ownedRes.next())
                owned.add(getWriter(ownedRes.getInt(1)));
            usageSearch.setInt(1, writerId);
            ResultSet usageRes = usageSearch.executeQuery();
            usageRes.next();
            MaybeMultihash target = Optional.ofNullable(usageRes.getBytes(1))
                    .map(x -> MaybeMultihash.of(Cid.cast(x)))
                    .orElse(MaybeMultihash.empty());
            return new WriterUsage(owner, target, usageRes.getLong(2), owned);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }

    }

    @Override
    public void updateWriterUsage(PublicKeyHash writer,
                                  MaybeMultihash target,
                                  Set<PublicKeyHash> ownedKeys,
                                  long retainedStorage) {
        int writerId = getWriterId(writer);
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement("UPDATE writerusage SET target=?, direct_size=? WHERE writer_id = ?;")) {
            insert.setBytes(1, target.isPresent() ? target.get().toBytes() : null);
            insert.setLong(2, retainedStorage);
            insert.setInt(3, writerId);
            int count = insert.executeUpdate();
            if (count != 1)
                throw new IllegalStateException("Didn't update one record!");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
        setWriters(writer, ownedKeys);
    }

    private void setWriters(PublicKeyHash writer, Set<PublicKeyHash> ownedWriters) {
        int writerId = getWriterId(writer);
        try (Connection conn = getConnection();
             PreparedStatement delete = conn.prepareStatement("DELETE FROM ownedkeys WHERE parent_id = ?;")) {
            delete.setInt(1, writerId);
            delete.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
        for (PublicKeyHash owned : ownedWriters) {
            try (Connection conn = getConnection();
                 PreparedStatement delete = conn.prepareStatement("INSERT INTO ownedkeys (parent_id, owned_id) VALUES(?, ?);")) {
                delete.setInt(1, writerId);
                int ownedId = getWriterId(owned);
                delete.setInt(2, ownedId);
                delete.execute();
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;
        isClosed = true;
    }
}
