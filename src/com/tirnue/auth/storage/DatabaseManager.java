package com.tirnue.auth.storage;

import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager {
    private final File dbFile;
    private final Logger logger;
    private Connection connection;

    private final Map<String, UserAccount> userCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Boolean> registeredCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> sessionCache = new java.util.concurrent.ConcurrentHashMap<>();

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
            // Enable WAL mode & performance tuning
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            stmt.execute("PRAGMA temp_store = MEMORY;");
            stmt.execute("PRAGMA cache_size = -16000;");
            stmt.execute("PRAGMA busy_timeout = 5000;");

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

    public boolean isRegistered(String username) {
        if (username == null) return false;
        String lower = username.toLowerCase();
        Boolean cached = registeredCache.get(lower);
        if (cached != null) {
            return cached;
        }
        if (userCache.containsKey(lower)) {
            registeredCache.put(lower, true);
            return true;
        }

        synchronized (this) {
            if (connection == null) return false;
            String sql = "SELECT 1 FROM users WHERE username = ? COLLATE NOCASE LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean exists = rs.next();
                    registeredCache.put(lower, exists);
                    return exists;
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to check if user is registered: " + username, e);
            }
        }
        return false;
    }

    public Optional<UserAccount> getUser(String username) {
        if (username == null) return Optional.empty();
        String lower = username.toLowerCase();
        UserAccount cached = userCache.get(lower);
        if (cached != null) {
            return Optional.of(cached);
        }

        synchronized (this) {
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

                        UserAccount acc = new UserAccount(username, uuid, pass, ip, lastLogin, regDate, type, manualOverride);
                        userCache.put(lower, acc);
                        registeredCache.put(lower, true);
                        return Optional.of(acc);
                    } else {
                        registeredCache.put(lower, false);
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to get user " + username, e);
            }
        }
        return Optional.empty();
    }

    public void saveUser(UserAccount user) {
        if (user == null) return;
        String lower = user.getUsername().toLowerCase();
        userCache.put(lower, user);
        registeredCache.put(lower, true);

        synchronized (this) {
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
    }

    public void deleteUser(String username) {
        if (username == null) return;
        String lower = username.toLowerCase();
        userCache.remove(lower);
        registeredCache.put(lower, false);

        synchronized (this) {
            if (connection == null) return;
            String sql = "DELETE FROM users WHERE username = ? COLLATE NOCASE";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to delete user " + username, e);
            }
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

    public boolean isSessionValid(String username, String ip) {
        if (username == null || ip == null) return false;
        String key = username.toLowerCase() + ":" + ip;
        Long cachedExp = sessionCache.get(key);
        if (cachedExp != null) {
            if (System.currentTimeMillis() < cachedExp) {
                return true;
            } else {
                sessionCache.remove(key);
            }
        }

        synchronized (this) {
            if (connection == null) return false;
            String sql = "SELECT expires_at FROM sessions WHERE username = ? COLLATE NOCASE AND ip = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long expiresAt = rs.getLong("expires_at");
                        if (System.currentTimeMillis() < expiresAt) {
                            sessionCache.put(key, expiresAt);
                            return true;
                        }
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to check session for " + username, e);
            }
        }
        return false;
    }

    public void createSession(String username, String ip, long durationMillis) {
        if (username == null || ip == null) return;
        String key = username.toLowerCase() + ":" + ip;
        long expiresAt = System.currentTimeMillis() + durationMillis;
        sessionCache.put(key, expiresAt);

        synchronized (this) {
            if (connection == null) return;
            String sql = "INSERT INTO sessions (username, ip, expires_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT(username) DO UPDATE SET ip = excluded.ip, expires_at = excluded.expires_at";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, ip);
                ps.setLong(3, expiresAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to create session for " + username, e);
            }
        }
    }

    public void invalidateSession(String username) {
        if (username == null) return;
        String prefix = username.toLowerCase() + ":";
        sessionCache.keySet().removeIf(k -> k.startsWith(prefix));

        synchronized (this) {
            if (connection == null) return;
            String sql = "DELETE FROM sessions WHERE username = ? COLLATE NOCASE";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to invalidate session for " + username, e);
            }
        }
    }

    public void clearCache() {
        userCache.clear();
        registeredCache.clear();
        sessionCache.clear();
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
