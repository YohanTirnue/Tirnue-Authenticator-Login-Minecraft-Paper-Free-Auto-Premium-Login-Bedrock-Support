package com.tirnue.auth.mojang;

import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MojangService {
    private static final String MOJANG_API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private final DatabaseManager db;
    private final Logger logger;
    private final long cacheExpiryMillis;
    private final Map<String, CachedMojangProfile> memoryCache = new ConcurrentHashMap<>();

    public static class CachedMojangProfile {
        private final String username;
        private final UUID uuid;
        private final boolean premium;
        private final long checkedAt;

        public CachedMojangProfile(String username, UUID uuid, boolean premium, long checkedAt) {
            this.username = username;
            this.uuid = uuid;
            this.premium = premium;
            this.checkedAt = checkedAt;
        }

        public String getUsername() {
            return username;
        }

        public UUID getUuid() {
            return uuid;
        }

        public boolean isPremium() {
            return premium;
        }

        public long getCheckedAt() {
            return checkedAt;
        }
    }

    public MojangService(DatabaseManager db, Logger logger, long cacheExpiryHours) {
        this.db = db;
        this.logger = logger;
        this.cacheExpiryMillis = cacheExpiryHours * 60L * 60L * 1000L;
    }

    public Optional<CachedMojangProfile> getOrFetchProfile(String username) {
        String lower = username.toLowerCase();

        // 1. Check in-memory cache
        CachedMojangProfile mem = memoryCache.get(lower);
        if (mem != null) {
            if (System.currentTimeMillis() - mem.getCheckedAt() < cacheExpiryMillis) {
                return Optional.of(mem);
            }
        }

        // 2. Check Database user account
        Optional<UserAccount> userOpt = db.getUser(username);
        if (userOpt.isPresent()) {
            UserAccount acc = userOpt.get();
            if (acc.isManualOverride()) {
                CachedMojangProfile p = new CachedMojangProfile(username, acc.getUuid(), acc.getAuthType() == UserAccount.AuthType.PREMIUM, System.currentTimeMillis());
                memoryCache.put(lower, p);
                return Optional.of(p);
            }
        }

        // 3. Query Mojang API
        Optional<UUID> mojangUuid = queryMojang(username);
        boolean isPremium = mojangUuid.isPresent();
        CachedMojangProfile profile = new CachedMojangProfile(username, mojangUuid.orElse(null), isPremium, System.currentTimeMillis());

        memoryCache.put(lower, profile);
        return Optional.of(profile);
    }

    public Optional<UUID> queryMojang(String username) {
        try {
            URI uri = URI.create(MOJANG_API_URL + username);
            URL url = uri.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TirnueAuth/1.0");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);

            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    String response = sb.toString();
                    Matcher matcher = ID_PATTERN.matcher(response);
                    if (matcher.find()) {
                        String rawId = matcher.group(1);
                        return Optional.of(parseTrimmedUuid(rawId));
                    }
                }
            } else if (code == 204 || code == 404) {
                return Optional.empty(); // Username does not exist on Mojang
            } else {
                logger.warning("Mojang API returned HTTP " + code + " for " + username);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to reach Mojang API for " + username + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    public static UUID parseTrimmedUuid(String trimmed) {
        if (trimmed == null || trimmed.length() != 32) {
            throw new IllegalArgumentException("Invalid UUID string: " + trimmed);
        }
        String formatted = trimmed.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        return UUID.fromString(formatted);
    }

    public void clearCache(String username) {
        if (username == null) {
            memoryCache.clear();
        } else {
            memoryCache.remove(username.toLowerCase());
        }
    }
}
