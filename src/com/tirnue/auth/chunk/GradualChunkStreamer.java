package com.tirnue.auth.chunk;

import com.tirnue.auth.TirnueAuth;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Progressively streams chunks to joining and teleporting players.
 *
 * Instead of flooding the socket with 441 chunks simultaneously on login
 * (which bottlenecks the Netty queue and triggers client KeepAlive lag spikes up to 1000ms),
 * this engine starts the player at a lightweight radius of 3 (49 chunks, instant load in <150ms)
 * and smoothly expands view distance in stages over ~6 seconds.
 */
public class GradualChunkStreamer implements Listener {
    private final TirnueAuth plugin;
    private final Map<UUID, BukkitTask> activeStreams = new ConcurrentHashMap<>();

    public GradualChunkStreamer(TirnueAuth plugin) {
        this.plugin = plugin;
    }

    public void startStream(Player player) {
        if (!plugin.getConfig().getBoolean("gradual-chunks.enabled", true)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        stopStream(uuid);

        int initialDistance = plugin.getConfig().getInt("gradual-chunks.initial-view-distance", 3);
        int stage1Distance = plugin.getConfig().getInt("gradual-chunks.stage-1-distance", 6);
        int stage2Distance = plugin.getConfig().getInt("gradual-chunks.stage-2-distance", 10);
        int configuredTarget = plugin.getConfig().getInt("gradual-chunks.target-view-distance", 14);
        long ticksPerStage = plugin.getConfig().getLong("gradual-chunks.ticks-per-stage", 40L); // 40 ticks = 2.0s

        int worldDistance = player.getWorld() != null ? player.getWorld().getViewDistance() : configuredTarget;
        final int targetDistance = Math.max(initialDistance, Math.min(configuredTarget, worldDistance));

        if (targetDistance <= initialDistance) {
            return;
        }

        // 1. Immediately clamp to lightweight initial view distance on login
        try {
            int sendDist = player.getSendViewDistance();
            if (sendDist <= 0 || sendDist > initialDistance) {
                player.setViewDistance(initialDistance);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not set initial view distance for " + player.getName() + ": " + t.getMessage());
            return;
        }

        // 2. Schedule progressive expansion stages (Stage 1 -> Stage 2 -> Target / World Default)
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int step = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopStream(uuid);
                    return;
                }

                step++;
                try {
                    // If WingFlightPlugin is actively clamping sendViewDistance (e.g. 4 while wearing wings),
                    // do not override the player's view distance above their wing clamp!
                    int currentSendDist = player.getSendViewDistance();
                    if (currentSendDist > 0 && currentSendDist <= 6) {
                        stopStream(uuid);
                        return;
                    }

                    if (step == 1) {
                        player.setViewDistance(Math.min(stage1Distance, targetDistance));
                    } else if (step == 2) {
                        player.setViewDistance(Math.min(stage2Distance, targetDistance));
                    } else if (step >= 3) {
                        player.setViewDistance(-1);
                        player.setSendViewDistance(-1);
                        stopStream(uuid);
                    }
                } catch (Throwable t) {
                    stopStream(uuid);
                }
            }
        }, ticksPerStage, ticksPerStage);

        activeStreams.put(uuid, task);
    }

    public void stopStream(UUID uuid) {
        BukkitTask task = activeStreams.remove(uuid);
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {}
        }
    }

    public void cancelAll() {
        for (BukkitTask task : activeStreams.values()) {
            try {
                task.cancel();
            } catch (Throwable ignored) {}
        }
        activeStreams.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        startStream(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        startStream(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getFrom().getWorld() == null || event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        // Cross-world teleports are handled by onWorldChange
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            return;
        }
        // Stream for intra-world teleports exceeding 64 blocks
        if (event.getFrom().distanceSquared(event.getTo()) > 64.0 * 64.0) {
            startStream(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopStream(event.getPlayer().getUniqueId());
    }
}
