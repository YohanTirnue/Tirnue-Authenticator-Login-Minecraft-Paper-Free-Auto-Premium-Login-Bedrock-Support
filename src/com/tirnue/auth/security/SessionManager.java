package com.tirnue.auth.security;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private final TirnueAuth plugin;
    private final DatabaseManager db;

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> preAuthenticated = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> reminderTasks = new ConcurrentHashMap<>();

    public SessionManager(TirnueAuth plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.contains(uuid);
    }

    public boolean isAuthenticated(Player player) {
        return player != null && isAuthenticated(player.getUniqueId());
    }

    public void markPreAuthenticated(UUID uuid) {
        preAuthenticated.add(uuid);
    }

    public boolean consumePreAuthenticated(UUID uuid) {
        return preAuthenticated.remove(uuid);
    }

    public void authenticate(Player player, String reason) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "127.0.0.1";

        authenticated.add(uuid);
        failedAttempts.remove(name.toLowerCase());

        // Cancel limbo tasks
        cancelTasks(uuid);

        // Remove blindness
        player.removePotionEffect(PotionEffectType.BLINDNESS);

        // Play level-up chime
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        } catch (Throwable ignored) {
        }

        // Update database and create IP session
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<UserAccount> userOpt = db.getUser(name);
            if (userOpt.isPresent()) {
                UserAccount user = userOpt.get();
                user.setLastLogin(System.currentTimeMillis());
                user.setIp(ip);
                if (user.getUuid() == null) {
                    user.setUuid(uuid);
                }
                db.saveUser(user);
            }

            int sessionTimeoutMin = plugin.getConfig().getInt("security.session-timeout-minutes", 30);
            if (sessionTimeoutMin > 0) {
                long durationMillis = sessionTimeoutMin * 60L * 1000L;
                db.createSession(name, ip, durationMillis);
            }
        });

        if (reason != null && !reason.isEmpty()) {
            String prefix = plugin.getPrefix();
            String formatted = reason.replace("{prefix}", prefix).replace("%prefix%", prefix);
            player.sendMessage(plugin.color(formatted));
        }
    }

    public void startLimbo(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        // Apply blindness effect if enabled
        if (plugin.getConfig().getBoolean("limbo.apply-blindness", true)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 999999, 0, false, false, false));
        }

        // Schedule kick timeout
        int timeoutSec = plugin.getConfig().getInt("security.login-timeout-seconds", 120);
        if (timeoutSec > 0) {
            BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!isAuthenticated(uuid) && player.isOnline()) {
                    String kickMsg = plugin.getMessage("timeout-kick", "&cᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛɪᴏɴ ᴛɪᴍᴇᴅ ᴏᴜᴛ. ᴘʟᴇᴀꜱᴇ ʟᴏɢ ɪɴ ᴘʀᴏᴍᴘᴛʟʏ ᴜᴘᴏɴ ᴊᴏɪɴɪɴɢ.");
                    player.kick(Component.text(plugin.stripColor(kickMsg)));
                }
            }, timeoutSec * 20L);
            timeoutTasks.put(uuid, timeoutTask);
        }

        // Schedule periodic reminder prompt
        int reminderSec = plugin.getConfig().getInt("reminder-interval-seconds", 5);
        if (reminderSec > 0) {
            BukkitTask reminderTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (isAuthenticated(uuid) || !player.isOnline()) {
                    cancelTasks(uuid);
                    return;
                }
                sendPrompt(player);
            }, 0L, reminderSec * 20L);
            reminderTasks.put(uuid, reminderTask);
        }
    }

    public void sendPrompt(Player player) {
        if (player == null || !player.isOnline()) return;
        String name = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean registered = db.isRegistered(name);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || isAuthenticated(player.getUniqueId())) return;
                if (registered) {
                    plugin.sendMessage(player, "login-prompt", "ꑪ &bᴘʟᴇᴀꜱᴇ ʟᴏɢ ɪɴ: &f/login <ᴘᴀꜱꜱᴡᴏʀᴅ>");
                } else {
                    plugin.sendMessage(player, "register-prompt", "ꑭ &eᴘʟᴇᴀꜱᴇ ʀᴇɢɪꜱᴛᴇʀ: &f/register <ᴘᴀꜱꜱᴡᴏʀᴅ> <ᴄᴏɴꜰɪʀᴍᴘᴀꜱꜱᴡᴏʀᴅ>");
                }
            });
        });
    }

    public boolean recordFailedAttempt(Player player) {
        if (player == null) return false;
        String name = player.getName().toLowerCase();
        int attempts = failedAttempts.getOrDefault(name, 0) + 1;
        failedAttempts.put(name, attempts);

        int maxAttempts = plugin.getConfig().getInt("security.max-failed-attempts", 5);

        try {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }

        if (attempts >= maxAttempts) {
            failedAttempts.remove(name);
            String kickMsg = plugin.getMessage("max-attempts-kick", "ꑬ &cᴛᴏᴏ ᴍᴀɴʏ ꜰᴀɪʟᴇᴅ ʟᴏɢɪɴ ᴀᴛᴛᴇᴍᴘᴛꜱ.");
            player.kick(Component.text(plugin.stripColor(kickMsg)));
            return true;
        }

        int remaining = maxAttempts - attempts;
        plugin.sendMessage(player, "wrong-password", "ꑬ &cɪɴᴄᴏʀʀᴇᴄᴛ ᴘᴀꜱꜱᴡᴏʀᴅ! ᴀᴛᴛᴇᴍᴘᴛꜱ ʀᴇᴍᴀɪɴɪɴɢ: &f{remaining}",
                "{remaining}", String.valueOf(remaining));
        return false;
    }

    public void cancelTasks(UUID uuid) {
        BukkitTask timeout = timeoutTasks.remove(uuid);
        if (timeout != null) {
            timeout.cancel();
        }
        BukkitTask reminder = reminderTasks.remove(uuid);
        if (reminder != null) {
            reminder.cancel();
        }
    }

    public void cleanup(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        authenticated.remove(uuid);
        preAuthenticated.remove(uuid);
        cancelTasks(uuid);
    }

    public void reload() {
        // Clear tasks and re-evaluate
        timeoutTasks.values().forEach(BukkitTask::cancel);
        timeoutTasks.clear();
        reminderTasks.values().forEach(BukkitTask::cancel);
        reminderTasks.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isAuthenticated(player.getUniqueId())) {
                startLimbo(player);
            }
        }
    }
}
