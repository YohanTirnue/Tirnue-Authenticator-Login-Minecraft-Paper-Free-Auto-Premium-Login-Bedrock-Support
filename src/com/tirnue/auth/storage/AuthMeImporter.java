package com.tirnue.auth.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AuthMeImporter {
    private final DatabaseManager db;
    private final Logger logger;
    private final File dataFolder;

    public AuthMeImporter(DatabaseManager db, Logger logger, File dataFolder) {
        this.db = db;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    public int importFromAuthMe(boolean force) {
        // Look for plugins/AuthMe/authme.db
        File pluginsDir = dataFolder.getParentFile();
        File authMeDbFile = new File(new File(pluginsDir, "AuthMe"), "authme.db");

        if (!authMeDbFile.exists()) {
            logger.info("No AuthMe database found at " + authMeDbFile.getAbsolutePath() + " to import.");
            return 0;
        }

        int currentUsers = db.getTotalUsers();
        if (currentUsers > 0 && !force) {
            logger.info("TirnueAuth database already contains " + currentUsers + " accounts. Skipping automatic AuthMe import (use /tauth import to force).");
            return 0;
        }

        logger.info("Found AuthMe database at " + authMeDbFile.getAbsolutePath() + ". Beginning account import...");

        int imported = 0;
        int bedrockCount = 0;
        int premiumCount = 0;
        int crackedCount = 0;

        String url = "jdbc:sqlite:" + authMeDbFile.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT realname, password, ip, lastlogin, regdate, premiumUUID FROM authme")) {

            while (rs.next()) {
                String realname = rs.getString("realname");
                String password = rs.getString("password");
                String ip = rs.getString("ip");
                long lastLogin = rs.getLong("lastlogin");
                long regDate = rs.getLong("regdate");
                String premUuidStr = rs.getString("premiumUUID");

                if (realname == null || realname.trim().isEmpty() || password == null) {
                    continue;
                }

                if (!force && db.getUser(realname).isPresent()) {
                    continue;
                }

                UserAccount.AuthType type;
                UUID uuid = null;

                if (realname.startsWith(".")) {
                    type = UserAccount.AuthType.BEDROCK;
                    bedrockCount++;
                } else if (premUuidStr != null && !premUuidStr.trim().isEmpty() && !"null".equalsIgnoreCase(premUuidStr)) {
                    type = UserAccount.AuthType.PREMIUM;
                    premiumCount++;
                    try {
                        uuid = UUID.fromString(premUuidStr.trim());
                    } catch (IllegalArgumentException ignored) {
                    }
                } else {
                    type = UserAccount.AuthType.CRACKED;
                    crackedCount++;
                }

                UserAccount account = new UserAccount(
                        realname,
                        uuid,
                        password,
                        ip != null ? ip : "127.0.0.1",
                        lastLogin > 0 ? lastLogin : System.currentTimeMillis(),
                        regDate > 0 ? regDate : System.currentTimeMillis(),
                        type,
                        false
                );

                db.saveUser(account);
                imported++;
            }

            logger.info("Successfully imported " + imported + " accounts from AuthMe! (" +
                    bedrockCount + " Bedrock, " + premiumCount + " Premium, " + crackedCount + " Cracked)");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to import accounts from AuthMe database: " + e.getMessage(), e);
        }

        return imported;
    }
}
