package com.tirnue.auth.listener;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.mojang.MojangService;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PlayerProtectionListener implements Listener {
    private static final Set<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList(
            "login", "l", "log", "register", "reg"
    ));

    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;
    private final MojangService mojangService;

    public PlayerProtectionListener(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager, MojangService mojangService) {
        this.plugin = plugin;
        this.db = db;
        this.sessionManager = sessionManager;
        this.mojangService = mojangService;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        // Already authenticated by Bedrock listener?
        if (sessionManager.isAuthenticated(uuid)) {
            return;
        }

        // Bedrock names start with '.'
        if (name.startsWith(".")) {
            return;
        }

        // Pre-authenticated via Paper Pre-Join Dialog?
        if (sessionManager.consumePreAuthenticated(uuid)) {
            sessionManager.authenticate(player, null);
            plugin.sendMessage(player, "login-success", "ꑫ &aᴛʜᴀɴᴋ ʏᴏᴜ ꜰᴏʀ ʟᴏɢɢɪɴɢ ɪɴ!");
            return;
        }

        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "127.0.0.1";

        // Check async session and mojang premium
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 1. Mojang Premium Auto-login (Official accounts get highest priority)
            if (plugin.getConfig().getBoolean("mojang.enabled", true)) {
                Optional<UserAccount> accOpt = db.getUser(name);
                UUID verifiedMojangUuid = (plugin.getMojangSessionVerifier() != null)
                        ? plugin.getMojangSessionVerifier().getVerifiedUuid(name)
                        : null;

                if (verifiedMojangUuid != null) {
                    final UUID finalUuid = verifiedMojangUuid;
                    final boolean isExisting = accOpt.isPresent();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            sessionManager.authenticate(player, null);
                            plugin.sendMessage(player, "mojang-welcome", "ꑫ &aʏᴏᴜ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇᴅ ᴛʜʀᴏᴜɢʜ ᴍᴏᴊᴀɴɢ, ʏᴏᴜ ᴀʀᴇ ᴀᴜᴛᴏᴍᴀᴛɪᴄᴀʟʟʏ ʟᴏɢɢᴇᴅ ɪɴ!");
                        }
                    });

                    // Ensure user is saved/updated in database as PREMIUM with Mojang UUID
                    if (!isExisting) {
                        UserAccount newAcc = new UserAccount(
                                name,
                                finalUuid,
                                "$PREMIUM$",
                                ip,
                                System.currentTimeMillis(),
                                System.currentTimeMillis(),
                                UserAccount.AuthType.PREMIUM,
                                false
                        );
                        db.saveUser(newAcc);
                    } else {
                        UserAccount acc = accOpt.get();
                        acc.setIp(ip);
                        acc.setLastLogin(System.currentTimeMillis());
                        if (acc.getAuthType() != UserAccount.AuthType.PREMIUM) {
                            acc.setAuthType(UserAccount.AuthType.PREMIUM);
                        }
                        acc.setUuid(finalUuid);
                        db.saveUser(acc);
                    }
                    return;
                } else if (accOpt.isPresent() && accOpt.get().getAuthType() == UserAccount.AuthType.PREMIUM) {
                    // Registered PREMIUM account failed verification!
                    // Cracked logins on premium accounts are strictly rejected!
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            String kickMsg = plugin.getMessage("premium-cracked-kick", "ꑬ &cᴛʜɪꜱ ᴀᴄᴄᴏᴜɴᴛ ɪꜱ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴀꜱ ᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴘʀᴇᴍɪᴜᴍ. ᴄʀᴀᴄᴋᴇᴅ ʟᴏɢɪɴꜱ ᴀʀᴇ ɴᴏᴛ ᴘᴇʀᴍɪᴛᴛᴇᴅ.");
                            player.kick(Component.text(plugin.stripColor(plugin.color(kickMsg))));
                        }
                    });
                    return;
                }
            }

            // 2. IP Session Auto-login (for cracked accounts returning on same IP)
            if (db.isSessionValid(name, ip)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        sessionManager.authenticate(player, null);
                        plugin.sendMessage(player, "session-restored", "ꑫ &aᴛʜᴀɴᴋ ʏᴏᴜ ꜰᴏʀ ʟᴏɢɢɪɴɢ ɪɴ!");
                    }
                });
                return;
            }

            // Otherwise, unauthenticated -> kick immediately! Dialog is strictly required!
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !sessionManager.isAuthenticated(uuid)) {
                    String kickMsg = plugin.getMessage("dialog-required-kick", "ꑬ &cʏᴏᴜ ᴍᴜꜱᴛ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇ ᴠɪᴀ ᴛʜᴇ ʟᴏɢɪɴ ᴅɪᴀʟᴏɢ.");
                    player.kick(Component.text(plugin.stripColor(plugin.color(kickMsg))));
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessionManager.cleanup(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (sessionManager.isAuthenticated(player.getUniqueId())) {
            return;
        }

        if (!plugin.getConfig().getBoolean("limbo.freeze-movement", true)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Allow rotation, freeze XYZ
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location loc = from.clone();
            loc.setYaw(to.getYaw());
            loc.setPitch(to.getPitch());
            event.setTo(loc);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!sessionManager.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                String kickMsg = plugin.getMessage("dialog-required-kick", "ꑬ &cʏᴏᴜ ᴍᴜꜱᴛ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇ ᴠɪᴀ ᴛʜᴇ ʟᴏɢɪɴ ᴅɪᴀʟᴏɢ.");
                player.kick(Component.text(plugin.stripColor(plugin.color(kickMsg))));
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage().trim();
        if (msg.startsWith("/")) {
            msg = msg.substring(1);
        }

        String[] parts = msg.split(" ", 2);
        String label = parts[0].toLowerCase();
        if (label.contains(":")) {
            label = label.substring(label.indexOf(":") + 1);
        }

        if (label.equals("logout") || label.equals("unlog")) {
            event.setCancelled(true);
            if (!sessionManager.isAuthenticated(player.getUniqueId())) {
                plugin.sendMessage(player, "not-logged-in", "ꑬ &cʏᴏᴜ ᴀʀᴇ ɴᴏᴛ ʟᴏɢɢᴇᴅ ɪɴ!");
                return;
            }
            String name = player.getName();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                db.invalidateSession(name);
            });
            sessionManager.cleanup(player);
            String logoutKick = plugin.getMessage("logout-kick", "ꑮ &dʏᴏᴜ ʜᴀᴠᴇ ʙᴇᴇɴ ʟᴏɢɢᴇᴅ ᴏᴜᴛ. ᴘʟᴇᴀꜱᴇ ʀᴇᴄᴏɴɴᴇᴄᴛ ᴛᴏ ʟᴏɢ ɪɴ ᴀɢᴀɪɴ.");
            player.kick(Component.text(plugin.stripColor(plugin.color(logoutKick))));
            return;
        }

        if (sessionManager.isAuthenticated(player.getUniqueId())) {
            return;
        }

        // If somehow in world unauthenticated, kick immediately!
        event.setCancelled(true);
        String kickMsg = plugin.getMessage("dialog-required-kick", "ꑬ &cʏᴏᴜ ᴍᴜꜱᴛ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇ ᴠɪᴀ ᴛʜᴇ ʟᴏɢɪɴ ᴅɪᴀʟᴏɢ.");
        player.kick(Component.text(plugin.stripColor(plugin.color(kickMsg))));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (!sessionManager.isAuthenticated(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (!sessionManager.isAuthenticated(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            // Unauthenticated players take NO damage (invulnerable while logging in)
            if (!sessionManager.isAuthenticated(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager) {
            if (!sessionManager.isAuthenticated(damager.getUniqueId())) {
                event.setCancelled(true);
            }
        }
        if (event.getEntity() instanceof Player victim) {
            if (!sessionManager.isAuthenticated(victim.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (!sessionManager.isAuthenticated(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            if (!sessionManager.isAuthenticated(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerFish(PlayerFishEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!sessionManager.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
