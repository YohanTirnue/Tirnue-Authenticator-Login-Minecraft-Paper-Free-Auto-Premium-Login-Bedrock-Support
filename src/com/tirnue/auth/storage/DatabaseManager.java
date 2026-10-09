package com.tirnue.auth.storage;

import java.io.File;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager {
    private final File dbFile;
    private final Logger logger;
    private Connection connection;

    public DatabaseManager(File dataFolder, Logger logger) {
        this.dbFile = new File(dataFolder, "TirnueAuth.db");
        this.logger = logger;
    }

    public synchronized void initialize() throws SQLException {
        if (!dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "SQLite JDBC driver not found", e);
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "username TEXT PRIMARY KEY COLLATE NOCASE," +
                    "uuid TEXT," +
                    "password_hash TEXT NOT NULL," +
                    "ip TEXT," +
                    "last_login INTEGER NOT NULL," +
                    "registration_date INTEGER NOT NULL," +
                    "auth_type TEXT NOT NULL," +
                    "manual_override INTEGER NOT NULL DEFAULT 0" +
                    ");");

            stmt.execute("CREATE TABLE IF NOT EXISTS sessions (" +
                    "username TEXT PRIMARY KEY COLLATE NOCASE," +
                    "ip TEXT NOT NULL," +
                    "expires_at INTEGER NOT NULL" +
                    ");");
        }
    }

    public synchronized boolean isRegistered(String username) {
        if (connection == null || username == null) return false;
        String sql = "SELECT 1 FROM users WHERE username = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to check if user is registered: " + username, e);
        }
        return false;
    }

    public synchronized Optional<UserAccount> getUser(String username) {
        if (connection == null) return Optional.empty();
        String sql = "SELECT uuid, password_hash, ip, last_login, registration_date, auth_type, manual_override FROM users WHERE username = ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String uuidStr = rs.getString("uuid");
                    UUID uuid = (uuidStr != null && !uuidStr.isEmpty()) ? UUID.fromString(uuidStr) : null;
                    String pass = rs.getString("password_hash");
                    String ip = rs.getString("ip");
                    long lastLogin = rs.getLong("last_login");
                    long regDate = rs.getLong("registration_date");
                    String typeStr = rs.getString("auth_type");
                    UserAccount.AuthType type = UserAccount.AuthType.valueOf(typeStr);
                    boolean manualOverride = rs.getInt("manual_override") == 1;

                    return Optional.of(new UserAccount(username, uuid, pass, ip, lastLogin, regDate, type, manualOverride));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get user " + username, e);
        }
        return Optional.empty();
    }

    public synchronized void saveUser(UserAccount user) {
        if (connection == null) return;
        String sql = "INSERT INTO users (username, uuid, password_hash, ip, last_login, registration_date, auth_type, manual_override) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(username) DO UPDATE SET " +
                "uuid = excluded.uuid, " +
                "password_hash = excluded.password_hash, " +
                "ip = excluded.ip, " +
                "last_login = excluded.last_login, " +
                "registration_date = excluded.registration_date, " +
                "auth_type = excluded.auth_type, " +
                "manual_override = excluded.manual_override";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getUuid() != null ? user.getUuid().toString() : null);
            ps.setString(3, user.getPasswordHash());
            ps.setString(4, user.getIp());
            ps.setLong(5, user.getLastLogin());
            ps.setLong(6, user.getRegistrationDate());
            ps.setString(7, user.getAuthType().name());
            ps.setInt(8, user.isManualOverride() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to save user " + user.getUsername(), e);
        }
    }

    public synchronized void deleteUser(String username) {
        if (connection == null) return;
        String sql = "DELETE FROM users WHERE username = ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to delete user " + username, e);
        }
    }

    public synchronized int countRegistrationsByIp(String ip) {
        if (connection == null || ip == null) return 0;
        String sql = "SELECT COUNT(*) FROM users WHERE ip = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to count registrations by IP", e);
        }
        return 0;
    }

    public synchronized boolean isSessionValid(String username, String ip) {
        if (connection == null || ip == null) return false;
        String sql = "SELECT expires_at FROM sessions WHERE username = ? COLLATE NOCASE AND ip = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    if (System.currentTimeMillis() < expiresAt) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to check session for " + username, e);
        }
        return false;
    }

    public synchronized void createSession(String username, String ip, long durationMillis) {
        if (connection == null || ip == null) return;
        String sql = "INSERT INTO sessions (username, ip, expires_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(username) DO UPDATE SET ip = excluded.ip, expires_at = excluded.expires_at";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, ip);
            ps.setLong(3, System.currentTimeMillis() + durationMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to create session for " + username, e);
        }
    }

    public synchronized void invalidateSession(String username) {
        if (connection == null) return;
        String sql = "DELETE FROM sessions WHERE username = ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to invalidate session for " + username, e);
        }
    }

    public synchronized int getTotalUsers() {
        if (connection == null) return 0;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
    }
}
