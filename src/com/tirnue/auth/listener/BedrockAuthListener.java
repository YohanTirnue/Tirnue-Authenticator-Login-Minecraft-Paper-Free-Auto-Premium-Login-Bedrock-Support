package com.tirnue.auth.listener;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.security.PasswordSecurity;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Optional;
import java.util.UUID;

public class BedrockAuthListener implements Listener {
    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;
    private boolean hasFloodgate = false;

    public BedrockAuthListener(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager) {
        this.plugin = plugin;
        this.db = db;
        this.sessionManager = sessionManager;

        if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            this.hasFloodgate = true;
        }
    }

    public boolean isBedrock(Player player) {
        if (player == null) return false;
        if (player.getName().startsWith(".")) return true;
        if (hasFloodgate) {
            try {
                return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())
                        || FloodgateApi.getInstance().isFloodgateId(player.getUniqueId());
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public boolean isBedrock(UUID uuid, String name) {
        if (name != null && name.startsWith(".")) return true;
        if (hasFloodgate && uuid != null) {
            try {
                return FloodgateApi.getInstance().isFloodgatePlayer(uuid)
                        || FloodgateApi.getInstance().isFloodgateId(uuid);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.getConfig().getBoolean("bedrock.prevent-dot-spoofing", true)) {
            return;
        }

        String name = event.getName();
        UUID uuid = event.getUniqueId();

        if (name != null && name.startsWith(".")) {
            boolean verifiedBedrock = false;
            if (hasFloodgate) {
                try {
                    verifiedBedrock = FloodgateApi.getInstance().isFloodgatePlayer(uuid)
                            || FloodgateApi.getInstance().isFloodgateId(uuid);
                } catch (Throwable ignored) {
                }
            }

            if (!verifiedBedrock) {
                String kickMsg = plugin.getMessage("dot-spoof-kick", "ꑬ &cᴀᴄᴄᴇꜱꜱ ᴅᴇɴɪᴇᴅ: ᴏɴʟʏ ᴠᴇʀɪꜰɪᴇᴅ ʙᴇᴅʀᴏᴄᴋ ᴘʟᴀʏᴇʀꜱ ᴄᴀɴ ᴜꜱᴇ ᴛʜᴇ '.' ᴘʀᴇꜰɪx.");
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text(plugin.stripColor(kickMsg)));
                plugin.getLogger().warning("Blocked Java player trying to use Bedrock '.' prefix: " + name + " (" + uuid + ")");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isBedrock(player)) {
            return;
        }

        String name = player.getName();
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "127.0.0.1";

        // Immediately authenticate synchronously with zero delay
        sessionManager.authenticate(player, null);
        plugin.sendMessage(player, "bedrock-welcome", "ꑫ &aʏᴏᴜ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇᴅ ᴛʜʀᴏᴜɢʜ ᴍᴏᴊᴀɴɢ, ʏᴏᴜ ᴀʀᴇ ᴀᴜᴛᴏᴍᴀᴛɪᴄᴀʟʟʏ ʟᴏɢɢᴇᴅ ɪɴ!");

        // Background account registration or update
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<UserAccount> userOpt = db.getUser(name);
            if (!userOpt.isPresent()) {
                // Auto-register Bedrock player with strong random hash
                String randomPass = UUID.randomUUID().toString().replace("-", "");
                String hash = PasswordSecurity.hashPassword(randomPass);

                UserAccount account = new UserAccount(
                        name,
                        uuid,
                        hash,
                        ip,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        UserAccount.AuthType.BEDROCK,
                        false
                );
                db.saveUser(account);
                plugin.getLogger().info("Auto-registered new Bedrock player: " + name + " (" + uuid + ")");
            } else {
                UserAccount account = userOpt.get();
                if (account.getAuthType() != UserAccount.AuthType.BEDROCK) {
                    account.setAuthType(UserAccount.AuthType.BEDROCK);
                    db.saveUser(account);
                }
            }
        });
    }
}
