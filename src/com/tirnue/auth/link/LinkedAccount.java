package com.tirnue.auth.link;

import java.util.UUID;

public class LinkedAccount {
    public enum MasterType {
        JAVA,
        BEDROCK
    }

    private final String bedrockName;
    private final UUID bedrockUuid;
    private final String javaName;
    private final UUID javaUuid;
    private final MasterType masterType;
    private final UUID masterUuid;
    private final UUID copyUuid;
    private final long linkedAt;

    public LinkedAccount(String bedrockName, UUID bedrockUuid, String javaName, UUID javaUuid, MasterType masterType, UUID masterUuid, UUID copyUuid, long linkedAt) {
        this.bedrockName = bedrockName;
        this.bedrockUuid = bedrockUuid;
        this.javaName = javaName;
        this.javaUuid = javaUuid;
        this.masterType = masterType;
        this.masterUuid = masterUuid;
        this.copyUuid = copyUuid;
        this.linkedAt = linkedAt;
    }

    public String getBedrockName() {
        return bedrockName;
    }

    public UUID getBedrockUuid() {
        return bedrockUuid;
    }

    public String getJavaName() {
        return javaName;
    }

    public UUID getJavaUuid() {
        return javaUuid;
    }

    public MasterType getMasterType() {
        return masterType;
    }

    public boolean isJavaMaster() {
        return masterType == MasterType.JAVA;
    }

    public boolean isBedrockMaster() {
        return masterType == MasterType.BEDROCK;
    }

    public UUID getMasterUuid() {
        return masterUuid;
    }

    public UUID getCopyUuid() {
        return copyUuid;
    }

    public String getMasterName() {
        return isJavaMaster() ? javaName : bedrockName;
    }

    public String getCopyName() {
        return isJavaMaster() ? bedrockName : javaName;
    }

    public long getLinkedAt() {
        return linkedAt;
    }
}
