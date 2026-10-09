package com.tirnue.auth.hook;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class PlaceholderHook extends PlaceholderExpansion {
    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;

    public PlaceholderHook(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager) {
        this.plugin = plugin;
        this.db = db;
        this.sessionManager = sessionManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "tirnueauth";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Tirnue";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        String p = params.toLowerCase();
        boolean isAuthed = sessionManager.isAuthenticated(player.getUniqueId());

        switch (p) {
            case "logged_in":
            case "is_logged_in":
            case "is_authenticated":
                return isAuthed ? "true" : "false";

            case "registered":
            case "is_registered":
                return db.isRegistered(player.getName()) ? "true" : "false";

            case "type":
            case "auth_type": {
                Optional<UserAccount> userOpt = db.getUser(player.getName());
                return userOpt.map(userAccount -> userAccount.getAuthType().name()).orElse("UNREGISTERED");
            }

            case "is_bedrock":
                return player.getName().startsWith(".") ? "true" : "false";

            default:
                return null;
        }
    }
}
