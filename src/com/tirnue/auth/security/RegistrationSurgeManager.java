package com.tirnue.auth.security;

import com.tirnue.auth.TirnueAuth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rapid Registration Velocity Tracker & Pre-Join Queue Manager.
 * Detects bot swarms attempting rapid registrations (e.g. 5 registrations in 100ms)
 * and locks registrations into a strictly throttled queue (e.g. 5 accounts per 5 minutes).
 */
public class RegistrationSurgeManager {
    private final TirnueAuth plugin;

    // Rolling registration timestamps
    private final ConcurrentLinkedQueue<Long> registrationTimestamps = new ConcurrentLinkedQueue<>();
    
    // Active surge state
    private volatile boolean surgeActive = false;
    private volatile long surgeActiveUntil = 0L;
    private volatile long lastAlertTime = 0L;
    private final AtomicInteger registrationsInCurrentBatch = new AtomicInteger(0);
    private volatile long batchResetTime = 0L;

    // Registration FIFO Queue
    private final List<UUID> queueOrder = Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, Long> queuedAt = new ConcurrentHashMap<>();

    public RegistrationSurgeManager(TirnueAuth plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("security.registration-surge.enabled", true);
    }

    public int getThreshold() {
        return plugin.getConfig().getInt("security.registration-surge.threshold", 5);
    }

    public long getWindowMs() {
        return plugin.getConfig().getLong("security.registration-surge.window-ms", 1000L);
    }

    public int getCooldownSeconds() {
        return plugin.getConfig().getInt("security.registration-surge.cooldown-seconds", 300);
    }

    public int getAllowedPerInterval() {
        return plugin.getConfig().getInt("security.registration-surge.allowed-per-interval", 5);
    }

    public long getMinHumanReactionMs() {
        return plugin.getConfig().getLong("security.registration-surge.min-human-reaction-ms", 800L);
    }

    /**
     * Checks if registration surge is currently active.
     */
    public boolean isSurgeActive() {
        if (!isEnabled()) return false;
        long now = System.currentTimeMillis();
        if (surgeActive) {
            if (now >= surgeActiveUntil) {
                surgeActive = false;
                queueOrder.clear();
                queuedAt.clear();
                registrationsInCurrentBatch.set(0);
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Seconds remaining on current surge lock.
     */
    public int getRemainingSurgeSeconds() {
        if (!isSurgeActive()) return 0;
        long rem = surgeActiveUntil - System.currentTimeMillis();
        return rem > 0 ? (int) (rem / 1000L) : 0;
    }

    /**
     * Records a successful registration and evaluates velocity.
     * Returns true if this registration tripped the surge alarm.
     */
    public synchronized boolean recordRegistration() {
        if (!isEnabled()) return false;
        long now = System.currentTimeMillis();
        registrationTimestamps.add(now);

        long window = getWindowMs();
        // Prune old timestamps
        while (!registrationTimestamps.isEmpty()) {
            Long head = registrationTimestamps.peek();
            if (head == null || now - head > window) {
                registrationTimestamps.poll();
            } else {
                break;
            }
        }

        int count = registrationTimestamps.size();
        int threshold = getThreshold();

        if (count >= threshold && !surgeActive) {
            // TRIP THE SURGE!
            surgeActive = true;
            surgeActiveUntil = now + (getCooldownSeconds() * 1000L);
            batchResetTime = surgeActiveUntil;
            registrationsInCurrentBatch.set(0);

            notifyAdmins(count, window);
            return true;
        }

        if (surgeActive) {
            registrationsInCurrentBatch.incrementAndGet();
        }

        return false;
    }

    /**
     * Adds an unregistered player to the queue.
     */
    public int addToQueue(UUID uuid) {
        synchronized (queueOrder) {
            if (!queueOrder.contains(uuid)) {
                queueOrder.add(uuid);
                queuedAt.put(uuid, System.currentTimeMillis());
            }
            return queueOrder.indexOf(uuid) + 1;
        }
    }

    /**
     * Gets 1-based position in queue.
     */
    public int getQueuePosition(UUID uuid) {
        synchronized (queueOrder) {
            int idx = queueOrder.indexOf(uuid);
            return idx >= 0 ? idx + 1 : addToQueue(uuid);
        }
    }

    /**
     * Removes player from queue.
     */
    public void removeFromQueue(UUID uuid) {
        synchronized (queueOrder) {
            queueOrder.remove(uuid);
            queuedAt.remove(uuid);
        }
    }

    /**
     * Calculates estimated wait in seconds based on batch quota.
     */
    public int getEstimatedWaitSeconds(UUID uuid) {
        int pos = getQueuePosition(uuid);
        int allowed = Math.max(1, getAllowedPerInterval());
        int batch = (pos - 1) / allowed;
        if (batch == 0) {
            return Math.min(30, getRemainingSurgeSeconds());
        }
        return batch * getCooldownSeconds();
    }

    /**
     * Checks if this player is currently allowed to register.
     */
    public boolean canRegister(UUID uuid) {
        if (!isSurgeActive()) return true;
        int pos = getQueuePosition(uuid);
        int allowed = getAllowedPerInterval();
        return pos <= allowed && registrationsInCurrentBatch.get() < allowed;
    }

    /**
     * Sends popup title, audio cue, and chat alert to all online admins.
     */
    public void notifyAdmins(int count, long windowMs) {
        long now = System.currentTimeMillis();
        if (now - lastAlertTime < 5000L) return; // Debounce alerts
        lastAlertTime = now;

        String consoleMsg = "[TirnueAuth] POSSIBLE BOTTING DETECTED: " + count + " registrations in " + windowMs + "ms! Registration queue active (" + getAllowedPerInterval() + " per " + (getCooldownSeconds() / 60) + "m).";
        plugin.getLogger().warning(consoleMsg);

        String chatAlert = plugin.getMessage("surge-admin-alert",
                "&cꑬ &c[ʙᴏᴛ ꜱᴜʀɢᴇ] &eᴘᴏꜱꜱɪʙʟᴇ ʙᴏᴛᴛɪɴɢ ᴅᴇᴛᴇᴄᴛᴇᴅ: &f{count} &eʀᴇɢɪꜱᴛʀᴀᴛɪᴏɴꜱ ɪɴ &f{ms}ᴍꜱ&e! ʀᴇɢɪꜱᴛʀᴀᴛɪᴏɴ qᴜᴇᴜᴇ ᴀᴄᴛɪᴠᴀᴛᴇᴅ.",
                "{count}", String.valueOf(count),
                "{ms}", String.valueOf(windowMs));

        String titleText = plugin.getConfig().getString("security.registration-surge.popup-title", "&#ff5555⚠ &c&lʙᴏᴛᴛɪɴɢ ꜱᴜʀɢᴇ ⚠");
        String subtitleText = plugin.getConfig().getString("security.registration-surge.popup-subtitle", "&e{count} registrations in {ms}ms &8- &bQueue Active")
                .replace("{count}", String.valueOf(count))
                .replace("{ms}", String.valueOf(windowMs));

        Component titleComp = plugin.deserializeComponent(titleText);
        Component subComp = plugin.deserializeComponent(subtitleText);
        Title title = Title.title(titleComp, subComp, Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800)));

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("tirnue.auth.admin")) {
                    player.sendMessage(plugin.color(chatAlert));
                    try {
                        player.showTitle(title);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    public void reset() {
        surgeActive = false;
        surgeActiveUntil = 0L;
        registrationTimestamps.clear();
        queueOrder.clear();
        queuedAt.clear();
        registrationsInCurrentBatch.set(0);
    }
}
