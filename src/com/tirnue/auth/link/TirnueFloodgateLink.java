package com.tirnue.auth.link;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.floodgate.api.link.LinkRequestResult;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.util.LinkedPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TirnueFloodgateLink implements PlayerLink {
    private final AccountLinkManager linkManager;

    public TirnueFloodgateLink(AccountLinkManager linkManager) {
        this.linkManager = linkManager;
    }

    @Override
    public void load() {}

    @Override
    public @NonNull CompletableFuture<LinkedPlayer> getLinkedPlayer(@NonNull UUID bedrockId) {
        LinkedAccount link = linkManager.getLinkByBedrockUuid(bedrockId);
        if (link != null && link.isJavaMaster()) {
            return CompletableFuture.completedFuture(
                LinkedPlayer.of(link.getBedrockName(), link.getMasterUuid(), bedrockId)
            );
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NonNull CompletableFuture<Boolean> isLinkedPlayer(@NonNull UUID bedrockId) {
        return CompletableFuture.completedFuture(linkManager.getLinkByBedrockUuid(bedrockId) != null);
    }

    @Override
    public @NonNull CompletableFuture<Void> linkPlayer(@NonNull UUID bedrockId, @NonNull UUID javaId, @NonNull String username) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NonNull CompletableFuture<Void> unlinkPlayer(@NonNull UUID javaId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NonNull CompletableFuture<?> createLinkRequest(@NonNull UUID javaId, @NonNull String javaUsername, @NonNull String bedrockUsername) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NonNull CompletableFuture<LinkRequestResult> verifyLinkRequest(@NonNull UUID bedrockId, @NonNull String javaUsername, @NonNull String bedrockUsername, @NonNull String code) {
        return CompletableFuture.completedFuture(LinkRequestResult.NO_LINK_REQUESTED);
    }

    @Override
    public String getName() {
        return "TirnueAuth";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public long getVerifyLinkTimeout() {
        return 300;
    }

    @Override
    public boolean isAllowLinking() {
        return true;
    }

    @Override
    public void stop() {}
}
