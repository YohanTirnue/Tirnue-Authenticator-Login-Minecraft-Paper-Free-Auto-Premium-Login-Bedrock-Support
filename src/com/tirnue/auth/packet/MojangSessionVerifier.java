package com.tirnue.auth.packet;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.mojang.MojangService;
import org.bukkit.Bukkit;

import javax.crypto.Cipher;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles RSA challenge generation, shared secret decryption, Minecraft SHA-1 server hash calculation,
 * and Mojang session server (hasJoined) cryptographic verification.
 */
public class MojangSessionVerifier {
    private static final String HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    private static final Pattern UUID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final long VERIFIED_TTL_MS = 60_000L;
    private static final long PENDING_TTL_MS = 30_000L;

    private final TirnueAuth plugin;
    private final Logger logger;
    private final KeyPair rsaKeyPair;
    private final SecureRandom secureRandom;

    private final ConcurrentHashMap<String, PendingVerification> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VerifiedSession> verified = new ConcurrentHashMap<>();

    public record PendingVerification(String username, UUID playerUUID, byte[] verifyToken, long startedAt) {}
    public record VerifiedSession(UUID mojangUuid, long verifiedAt) {}

    public MojangSessionVerifier(TirnueAuth plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.secureRandom = new SecureRandom();
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(1024, this.secureRandom);
            this.rsaKeyPair = gen.generateKeyPair();
            logger.info("Generated 1024-bit RSA key pair for Mojang session verification.");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA algorithm not available in current JVM", e);
        }
    }

    public PublicKey getPublicKey() {
        return this.rsaKeyPair.getPublic();
    }

    public byte[] startVerification(String connectionKey, String username, UUID playerUUID) {
        evictStalePendingEntries();
        byte[] verifyToken = new byte[4];
        this.secureRandom.nextBytes(verifyToken);
        this.pending.put(connectionKey, new PendingVerification(username, playerUUID, verifyToken, System.currentTimeMillis()));
        return verifyToken;
    }

    private void evictStalePendingEntries() {
        long now = System.currentTimeMillis();
        this.pending.entrySet().removeIf(e -> now - e.getValue().startedAt() > PENDING_TTL_MS);
    }

    public boolean hasPending(String connectionKey) {
        return this.pending.containsKey(connectionKey);
    }

    public String getPendingUsername(String connectionKey) {
        PendingVerification pend = this.pending.get(connectionKey);
        return pend != null ? pend.username() : null;
    }

    public UUID getPendingPlayerUUID(String connectionKey) {
        PendingVerification pend = this.pending.get(connectionKey);
        return pend != null ? pend.playerUUID() : null;
    }

    public void cleanupPending(String connectionKey) {
        this.pending.remove(connectionKey);
    }

    public byte[] decryptData(byte[] encrypted) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, this.rsaKeyPair.getPrivate());
        return cipher.doFinal(encrypted);
    }

    public CompletableFuture<Optional<UUID>> completeVerification(String connectionKey, byte[] sharedSecret, byte[] encVerifyToken) {
        PendingVerification pend = this.pending.remove(connectionKey);
        if (pend == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        CompletableFuture<Optional<UUID>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[] decryptedToken = decryptData(encVerifyToken);
                if (!Arrays.equals(decryptedToken, pend.verifyToken())) {
                    logger.warning("Verify token mismatch during premium verification for '" + pend.username() + "'");
                    future.complete(Optional.empty());
                    return;
                }

                String serverHash = computeServerHash(sharedSecret);
                Optional<UUID> mojangUuid = queryHasJoined(pend.username(), serverHash);
                future.complete(mojangUuid);
            } catch (Exception e) {
                logger.warning("Mojang session verification failed for '" + pend.username() + "': " + e.getMessage());
                future.complete(Optional.empty());
            }
        });
        return future;
    }

    public Optional<UUID> queryHasJoined(String username, String serverHash) {
        try {
            String url = HAS_JOINED_URL + "?username=" + username + "&serverId=" + serverHash;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TirnueAuth/1.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    String body = sb.toString();
                    Matcher matcher = UUID_PATTERN.matcher(body);
                    if (matcher.find()) {
                        String rawId = matcher.group(1);
                        return Optional.of(MojangService.parseTrimmedUuid(rawId));
                    }
                }
            } else if (code == 204 || code == 404) {
                // Not authenticated with Mojang session server
                return Optional.empty();
            } else {
                logger.warning("Mojang hasJoined returned HTTP " + code + " for '" + username + "'");
                return Optional.empty();
            }
        } catch (Exception e) {
            logger.warning("Failed to contact Mojang session server for '" + username + "': " + e.getMessage());
            return Optional.empty();
        }
        return Optional.empty();
    }

    public void storeVerified(String username, UUID mojangUuid) {
        this.verified.put(username.toLowerCase(Locale.ROOT), new VerifiedSession(mojangUuid, System.currentTimeMillis()));
    }

    public UUID getVerifiedUuid(String username) {
        if (username == null) return null;
        String key = username.toLowerCase(Locale.ROOT);
        VerifiedSession session = this.verified.get(key);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() - session.verifiedAt() > VERIFIED_TTL_MS) {
            this.verified.remove(key);
            return null;
        }
        return session.mojangUuid();
    }

    public boolean isVerified(String username) {
        return getVerifiedUuid(username) != null;
    }

    public UUID consumeVerified(String username) {
        if (username == null) return null;
        String key = username.toLowerCase(Locale.ROOT);
        VerifiedSession session = this.verified.remove(key);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() - session.verifiedAt() > VERIFIED_TTL_MS) {
            return null;
        }
        return session.mojangUuid();
    }

    private String computeServerHash(byte[] sharedSecret) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update("".getBytes(StandardCharsets.ISO_8859_1));
        sha1.update(sharedSecret);
        sha1.update(this.rsaKeyPair.getPublic().getEncoded());
        return new BigInteger(sha1.digest()).toString(16);
    }
}
