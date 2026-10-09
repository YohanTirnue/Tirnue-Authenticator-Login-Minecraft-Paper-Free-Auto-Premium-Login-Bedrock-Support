package com.tirnue.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class PasswordSecurity {
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String hashPassword(String plainPassword) {
        String salt = generateSalt(16);
        String hash = computeAuthMeSha256(plainPassword, salt);
        return "$SHA$" + salt + "$" + hash;
    }

    public static boolean checkPassword(String plainPassword, String storedHash) {
        if (storedHash == null || plainPassword == null) return false;

        // AuthMe $SHA$<salt>$<hash> format
        if (storedHash.startsWith("$SHA$")) {
            String[] parts = storedHash.split("\\$");
            if (parts.length >= 4) {
                String salt = parts[2];
                String expectedHash = parts[3];
                String actualHash = computeAuthMeSha256(plainPassword, salt);
                return MessageDigest.isEqual(
                        expectedHash.getBytes(StandardCharsets.UTF_8),
                        actualHash.getBytes(StandardCharsets.UTF_8)
                );
            }
        }

        // Direct SHA-256 fallback
        String simpleHash = sha256Hex(plainPassword);
        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8),
                simpleHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String computeAuthMeSha256(String password, String salt) {
        String passHash = sha256Hex(password);
        return sha256Hex(passHash + salt);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private static String generateSalt(int length) {
        byte[] saltBytes = new byte[length / 2];
        RANDOM.nextBytes(saltBytes);
        return bytesToHex(saltBytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
