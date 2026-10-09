package com.tirnue.auth.storage;

import java.util.UUID;

public class UserAccount {
    public enum AuthType {
        CRACKED,
        PREMIUM,
        BEDROCK
    }

    private final String username;
    private UUID uuid;
    private String passwordHash;
    private String ip;
    private long lastLogin;
    private long registrationDate;
    private AuthType authType;
    private boolean manualOverride;

    public UserAccount(String username, UUID uuid, String passwordHash, String ip,
                       long lastLogin, long registrationDate, AuthType authType, boolean manualOverride) {
        this.username = username;
        this.uuid = uuid;
        this.passwordHash = passwordHash;
        this.ip = ip;
        this.lastLogin = lastLogin;
        this.registrationDate = registrationDate;
        this.authType = authType;
        this.manualOverride = manualOverride;
    }

    public String getUsername() {
        return username;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public long getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(long lastLogin) {
        this.lastLogin = lastLogin;
    }

    public long getRegistrationDate() {
        return registrationDate;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public boolean isManualOverride() {
        return manualOverride;
    }

    public void setManualOverride(boolean manualOverride) {
        this.manualOverride = manualOverride;
    }
}
