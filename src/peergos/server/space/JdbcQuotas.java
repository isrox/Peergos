package peergos.server.space;

import peergos.server.sql.*;
import peergos.server.util.Logging;

import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcQuotas {
	private static final Logger LOG = Logging.LOG();

    private static final String QUOTA_USER_NAME = "name";
    private static final String QUOTA_SIZE = "quota";
    private static final String SET_QUOTA = "UPDATE freequotas SET quota = ? WHERE name = ?;";
    private static final String GET_QUOTA = "SELECT quota FROM freequotas WHERE name = ?;";
    private static final String GET_ALL_QUOTAS = "SELECT name, quota FROM freequotas;";
    private static final String REMOVE_USER = "DELETE FROM freequotas WHERE name = ?;";

    private final SqlSupplier commands;
    private volatile boolean isClosed;
    private Supplier<Connection> conn;

    public JdbcQuotas(Supplier<Connection> conn, SqlSupplier commands) {
        this.commands = commands;
        this.conn = conn;
        init(commands);
    }

    private Connection getConnection() {
        Connection connection = conn.get();
        try {
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        if (isClosed)
            return;

        try (Connection conn = getConnection()) {
            commands.createTable(commands.createQuotasTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setQuota(String username, long quota) {
        try (Connection conn = getConnection();
             PreparedStatement createuser = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO freequotas (name, quota) VALUES(?, ?)"));
             PreparedStatement update = conn.prepareStatement(SET_QUOTA)) {
            createuser.setString(1, username);
            createuser.setLong(2, 0);
            createuser.executeUpdate();

            update.setLong(1, quota);
            update.setString(2, username);
            update.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public long getQuota(String username) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_QUOTA)) {
            select.setString(1, username);
            ResultSet rs = select.executeQuery();
            if (rs.next())
                return rs.getLong(QUOTA_SIZE);

            throw new IllegalStateException("No quota listed for user!");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public void removeUser(String username) {
        try (Connection conn = getConnection();
             PreparedStatement delete = conn.prepareStatement(REMOVE_USER)) {
            delete.setString(1, username);
            delete.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public Map<String, Long> getQuotas() {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_ALL_QUOTAS)) {
            ResultSet rs = select.executeQuery();
            Map<String, Long> res = new HashMap<>();
            while (rs.next()) {
                String username = rs.getString(QUOTA_USER_NAME);
                long quota = rs.getLong(QUOTA_SIZE);
                res.put(username, quota);
            }
            return res;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public boolean hasUser(String username) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_QUOTA)) {
            select.setString(1, username);
            ResultSet rs = select.executeQuery();
            return rs.next();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public int numberOfUsers() {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_ALL_QUOTAS)) {
            ResultSet rs = select.executeQuery();
            int count = 0;
            while (rs.next()) {
                count++;
            }
            return count;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;
        isClosed = true;
    }

    public static JdbcQuotas build(Supplier<Connection> conn, SqlSupplier commands) {
        return new JdbcQuotas(conn, commands);
    }
}
