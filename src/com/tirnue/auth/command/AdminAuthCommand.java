package com.tirnue.auth.command;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.mojang.MojangService;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.AuthMeImporter;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class AdminAuthCommand implements CommandExecutor, TabCompleter {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;
    private final MojangService mojangService;
    private final AuthMeImporter importer;

    public AdminAuthCommand(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager,
                            MojangService mojangService, AuthMeImporter importer) {
        this.plugin = plugin;
        this.db = db;
        this.sessionManager = sessionManager;
        this.mojangService = mojangService;
        this.importer = importer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("tirnue.auth.admin")) {
            plugin.sendMessage(sender, "no-permission", "ꑬ &cʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪꜱꜱɪᴏɴ ᴛᴏ ᴇxᴇᴄᴜᴛᴇ ᴛʜɪꜱ ᴄᴏᴍᴍᴀɴᴅ.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload": {
                plugin.reloadConfig();
                plugin.loadMessages();
                plugin.applyMojangConfig();
                mojangService.clearCache(null);
                sessionManager.reload();
                plugin.registerDynamicLogout();
                plugin.sendMessage(sender, "admin-reload-success", "ꑫ &aᴄᴏɴꜰɪɢᴜʀᴀᴛɪᴏɴ ᴀɴᴅ ᴄᴀᴄʜᴇ ʀᴇʟᴏᴀᴅᴇᴅ ꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ!");
                return true;
            }

            case "import": {
                plugin.sendMessage(sender, "admin-import-started", "ꑭ &eꜱᴛᴀʀᴛɪɴɢ ᴀᴜᴛʜᴍᴇ ᴅᴀᴛᴀʙᴀꜱᴇ ɪᴍᴘᴏʀᴛ...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    int count = importer.importFromAuthMe(true);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.sendMessage(sender, "admin-import-success", "ꑫ &aꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ ɪᴍᴘᴏʀᴛᴇᴅ &f{count} &aᴀᴄᴄᴏᴜɴᴛꜱ ꜰʀᴏᴍ ᴀᴜᴛʜᴍᴇ!",
                                "{count}", String.valueOf(count));
                    });
                });
                return true;
            }

            case "check": {
                if (args.length < 2) {
                    plugin.sendMessage(sender, "admin-check-usage", "ꑪ &bᴜꜱᴀɢᴇ: &f/{label} check <player>", "{label}", label);
                    return true;
                }
                String target = args[1];

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Optional<UserAccount> userOpt = db.getUser(target);
                    Player online = Bukkit.getPlayerExact(target);
                    boolean isOnline = online != null && online.isOnline();
                    boolean isAuthed = isOnline && sessionManager.isAuthenticated(online.getUniqueId());

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(plugin.color("&8&m----------------------------------------"));
                        sender.sendMessage(plugin.color("&#55cdfcᴛɪʀɴᴜᴇᴀᴜᴛʜ ꜱᴛᴀᴛᴜꜱ: &f" + target));
                        if (userOpt.isPresent()) {
                            UserAccount u = userOpt.get();
                            sender.sendMessage(plugin.color("&7- ʀᴇɢɪꜱᴛᴇʀᴇᴅ: &aʏᴇꜱ"));
                            sender.sendMessage(plugin.color("&7- ᴀᴜᴛʜ ᴍᴏᴅᴇ: &b" + u.getAuthType().name()));
                            sender.sendMessage(plugin.color("&7- ᴜᴜɪᴅ: &f" + (u.getUuid() != null ? u.getUuid().toString() : "None")));
                            sender.sendMessage(plugin.color("&7- ʟᴀꜱᴛ ɪᴘ: &f" + u.getIp()));
                            sender.sendMessage(plugin.color("&7- ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴅᴀᴛᴇ: &f" + DATE_FORMAT.format(new Date(u.getRegistrationDate()))));
                            sender.sendMessage(plugin.color("&7- ʟᴀꜱᴛ ʟᴏɢɪɴ: &f" + DATE_FORMAT.format(new Date(u.getLastLogin()))));
                            sender.sendMessage(plugin.color("&7- ᴍᴀɴᴜᴀʟ ᴏᴠᴇʀʀɪᴅᴇ: &f" + (u.isManualOverride() ? "&6ʏᴇꜱ" : "&7ɴᴏ")));
                        } else {
                            sender.sendMessage(plugin.color("&7- ʀᴇɢɪꜱᴛᴇʀᴇᴅ: &cɴᴏ"));
                        }
                        sender.sendMessage(plugin.color("&7- ᴏɴʟɪɴᴇ: " + (isOnline ? "&aʏᴇꜱ" : "&7ɴᴏ")));
                        sender.sendMessage(plugin.color("&7- ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇᴅ: " + (isAuthed ? "&aʏᴇꜱ" : "&cɴᴏ")));

                        if (target.startsWith(".")) {
                            sender.sendMessage(plugin.color("&7- ꜰʟᴏᴏᴅɢᴀᴛᴇ ʙᴇᴅʀᴏᴄᴋ: &eʏᴇꜱ (ᴘʀᴇꜰɪx ᴅᴇᴛᴇᴄᴛᴇᴅ)"));
                        } else if (online != null && Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
                            try {
                                boolean floodgate = FloodgateApi.getInstance().isFloodgatePlayer(online.getUniqueId());
                                sender.sendMessage(plugin.color("&7- ꜰʟᴏᴏᴅɢᴀᴛᴇ ʙᴇᴅʀᴏᴄᴋ: " + (floodgate ? "&eʏᴇꜱ" : "&7ɴᴏ")));
                            } catch (Throwable ignored) {
                            }
                        }

                        sender.sendMessage(plugin.color("&8&m----------------------------------------"));
                    });
                });
                return true;
            }

            case "set": {
                if (args.length < 3) {
                    plugin.sendMessage(sender, "admin-set-usage", "{prefix}&cᴜꜱᴀɢᴇ: &e/{label} set <player> <premium|cracked|bedrock>", "{label}", label);
                    return true;
                }
                String target = args[1];
                String modeStr = args[2].toUpperCase();

                UserAccount.AuthType newType;
                try {
                    newType = UserAccount.AuthType.valueOf(modeStr);
                } catch (IllegalArgumentException e) {
                    plugin.sendMessage(sender, "admin-set-invalid-type", "ꑬ &cɪɴᴠᴀʟɪᴅ ᴛʏᴘᴇ. ᴠᴀʟɪᴅ ᴛʏᴘᴇꜱ: &ePREMIUM, CRACKED, BEDROCK");
                    return true;
                }

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Optional<UserAccount> userOpt = db.getUser(target);
                    if (!userOpt.isPresent()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.sendMessage(sender, "admin-set-not-registered", "ꑬ &cᴘʟᴀʏᴇʀ &f{player} &cɪꜱ ɴᴏᴛ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ɪɴ ᴛʜᴇ ᴅᴀᴛᴀʙᴀꜱᴇ.",
                                    "{player}", target);
                        });
                        return;
                    }

                    UserAccount user = userOpt.get();
                    user.setAuthType(newType);
                    user.setManualOverride(true);
                    db.saveUser(user);

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.sendMessage(sender, "admin-set-success", "ꑫ &aꜱᴇᴛ ᴀᴜᴛʜ ᴍᴏᴅᴇ ꜰᴏʀ &f{player} &aᴛᴏ &b{type}&a.",
                                "{player}", target, "{type}", newType.name());
                    });
                });
                return true;
            }

            case "unregister": {
                if (args.length < 2) {
                    plugin.sendMessage(sender, "admin-unregister-usage", "ꑪ &bᴜꜱᴀɢᴇ: &f/{label} unregister <player>", "{label}", label);
                    return true;
                }
                String target = args[1];

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    db.deleteUser(target);
                    db.invalidateSession(target);

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player online = Bukkit.getPlayerExact(target);
                        if (online != null && online.isOnline()) {
                            sessionManager.cleanup(online);
                            sessionManager.startLimbo(online);
                        }
                        plugin.sendMessage(sender, "admin-unregister-success", "ꑮ &dꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ ᴜɴʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴘʟᴀʏᴇʀ &f{player}&d.",
                                "{player}", target);
                    });
                });
                return true;
            }

            case "forcelogin": {
                if (args.length < 2) {
                    plugin.sendMessage(sender, "admin-forcelogin-usage", "ꑪ &bᴜꜱᴀɢᴇ: &f/{label} forcelogin <player>", "{label}", label);
                    return true;
                }
                String target = args[1];
                Player online = Bukkit.getPlayerExact(target);
                if (online == null || !online.isOnline()) {
                    plugin.sendMessage(sender, "admin-forcelogin-not-online", "ꑬ &cᴘʟᴀʏᴇʀ &f{player} &cɪꜱ ɴᴏᴛ ᴄᴜʀʀᴇɴᴛʟʏ ᴏɴʟɪɴᴇ.",
                            "{player}", target);
                    return true;
                }

                sessionManager.authenticate(online, plugin.getMessage("admin-forcelogin-target", "ꑫ &aʏᴏᴜ ʜᴀᴠᴇ ʙᴇᴇɴ ꜰᴏʀᴄᴇꜰᴜʟʟʏ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇᴅ ʙʏ ᴀɴ ᴀᴅᴍɪɴɪꜱᴛʀᴀᴛᴏʀ."));
                plugin.sendMessage(sender, "admin-forcelogin-admin-success", "ꑫ &aᴘʟᴀʏᴇʀ &f{player} &aʜᴀꜱ ʙᴇᴇɴ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇᴅ.",
                        "{player}", target);
                return true;
            }

            case "status": {
                sender.sendMessage(plugin.color("&8&m----------------------------------------"));
                sender.sendMessage(plugin.color("&#55cdfcᴛɪʀɴᴜᴇᴀᴜᴛʜ ꜱʏꜱᴛᴇᴍ ꜱᴛᴀᴛᴜꜱ"));

                boolean ipBind = plugin.getConfig().getBoolean("mojang.premium-ip-binding", true);
                sender.sendMessage(plugin.color("&7- ᴘʀᴇᴍɪᴜᴍ ɪᴘ-ʙɪɴᴅɪɴɢ: " + (ipBind ? "&a[ENABLED]" : "&c[DISABLED]")));

                boolean surgeOn = mojangService.isSurgeProtectionEnabled();
                int surgeThresh = mojangService.getSurgeThreshold();
                boolean surgeActive = mojangService.isSurgeActive();
                int recentHandshakes = mojangService.getRecentHandshakesCount();
                sender.sendMessage(plugin.color("&7- ᴊᴏɪɴ ꜱᴜʀɢᴇ ᴘʀᴏᴛᴇᴄᴛɪᴏɴ: " + (surgeOn ? "&a[ENABLED]" : "&c[DISABLED]") +
                        " &8(&7Threshold: &f" + surgeThresh + " conn/s&8, &7Current: &f" + recentHandshakes + " conn/s&8, &7Surging: " + (surgeActive ? "&cYES" : "&aNO") + "&8)"));

                boolean rlOn = mojangService.isRateLimiterEnabled();
                double refillRate = mojangService.getRefillRatePerSec();
                int burst = mojangService.getBurstCapacity();
                int tokens = mojangService.getAvailableTokens();
                sender.sendMessage(plugin.color("&7- ᴍᴏᴊᴀɴɢ ᴀᴘɪ ʀᴀᴛᴇ ʟɪᴍɪᴛᴇʀ: " + (rlOn ? "&a[ENABLED]" : "&c[DISABLED]") +
                        " &8(&7Tokens: &f" + tokens + "/" + burst + "&8, &7Refill: &f" + refillRate + "/s&8)"));

                boolean circuitBreaker = mojangService.isCircuitBreakerTripped();
                sender.sendMessage(plugin.color("&7- ᴍᴏᴊᴀɴɢ ᴄɪʀᴄᴜɪᴛ ʙʀᴇᴀᴋᴇʀ: " + (circuitBreaker ? "&c[TRIPPED / PAUSED]" : "&a[HEALTHY / ACTIVE]")));

                sender.sendMessage(plugin.color("&8&m----------------------------------------"));
                return true;
            }

            case "ipbind": {
                boolean current = plugin.getConfig().getBoolean("mojang.premium-ip-binding", true);
                boolean newState;
                if (args.length >= 2) {
                    String val = args[1].toLowerCase();
                    if (val.equals("on") || val.equals("true") || val.equals("enable")) {
                        newState = true;
                    } else if (val.equals("off") || val.equals("false") || val.equals("disable")) {
                        newState = false;
                    } else {
                        sender.sendMessage(plugin.color("ꑪ &bᴜꜱᴀɢᴇ: &f/" + label + " ipbind [on|off]"));
                        return true;
                    }
                } else {
                    newState = !current;
                }
                plugin.getConfig().set("mojang.premium-ip-binding", newState);
                plugin.saveConfig();
                sender.sendMessage(plugin.color("ꑫ &aᴘʀᴇᴍɪᴜᴍ ɪᴘ-ʙɪɴᴅɪɴɢ ɪꜱ ɴᴏᴡ: " + (newState ? "&2[ENABLED]" : "&c[DISABLED]")));
                return true;
            }

            case "surge": {
                boolean current = mojangService.isSurgeProtectionEnabled();
                boolean newState;
                if (args.length >= 2) {
                    String val = args[1].toLowerCase();
                    if (val.equals("on") || val.equals("true") || val.equals("enable")) {
                        newState = true;
                    } else if (val.equals("off") || val.equals("false") || val.equals("disable")) {
                        newState = false;
                    } else {
                        sender.sendMessage(plugin.color("ꑪ &bᴜꜱᴀɢᴇ: &f/" + label + " surge [on|off]"));
                        return true;
                    }
                } else {
                    newState = !current;
                }
                mojangService.setSurgeProtectionEnabled(newState);
                plugin.getConfig().set("mojang.surge-protection.enabled", newState);
                plugin.saveConfig();
                sender.sendMessage(plugin.color("ꑫ &aᴊᴏɪɴ ꜱᴜʀɢᴇ ᴘʀᴏᴛᴇᴄᴛɪᴏɴ ɪꜱ ɴᴏᴡ: " + (newState ? "&2[ENABLED]" : "&c[DISABLED]")));
                return true;
            }

            case "ratelimit": {
                boolean current = mojangService.isRateLimiterEnabled();
                boolean newState;
                if (args.length >= 2) {
                    String val = args[1].toLowerCase();
                    if (val.equals("on") || val.equals("true") || val.equals("enable")) {
                        newState = true;
                    } else if (val.equals("off") || val.equals("false") || val.equals("disable")) {
                        newState = false;
                    } else {
                        sender.sendMessage(plugin.color("ꑪ &bᴜꜱᴀɢᴇ: &f/" + label + " ratelimit [on|off]"));
                        return true;
                    }
                } else {
                    newState = !current;
                }
                mojangService.setRateLimiterEnabled(newState);
                plugin.getConfig().set("mojang.rate-limiter.enabled", newState);
                plugin.saveConfig();
                sender.sendMessage(plugin.color("ꑫ &aᴍᴏᴊᴀɴɢ ᴀᴘɪ ʀᴀᴛᴇ ʟɪᴍɪᴛᴇʀ ɪꜱ ɴᴏᴡ: " + (newState ? "&2[ENABLED]" : "&c[DISABLED]")));
                return true;
            }

            default:
                sendHelp(sender, label);
                return true;
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(plugin.color("&8&m----------------------------------------"));
        sender.sendMessage(plugin.color("&#55cdfcᴛɪʀɴᴜᴇᴀᴜᴛʜ &7v" + plugin.getDescription().getVersion() + " &8- &bᴀᴅᴍɪɴ ᴘᴀɴᴇʟ"));
        sender.sendMessage(plugin.color("&3/" + label + " status &7- ᴠɪᴇᴡ ꜱᴇᴄᴜʀɪᴛʏ & ʀᴀᴛᴇ-ʟɪᴍɪᴛ ꜱᴛᴀᴛᴜꜱ"));
        sender.sendMessage(plugin.color("&3/" + label + " ipbind [on|off] &7- ᴛᴏɢɢʟᴇ ᴘʀᴇᴍɪᴜᴍ ɪᴘ-ʙɪɴᴅɪɴɢ"));
        sender.sendMessage(plugin.color("&3/" + label + " surge [on|off] &7- ᴛᴏɢɢʟᴇ ᴊᴏɪɴ ꜱᴜʀɢᴇ ᴘʀᴏᴛᴇᴄᴛɪᴏɴ"));
        sender.sendMessage(plugin.color("&3/" + label + " ratelimit [on|off] &7- ᴛᴏɢɢʟᴇ ᴍᴏᴊᴀɴɢ ᴀᴘɪ ʀᴀᴛᴇ ʟɪᴍɪᴛᴇʀ"));
        sender.sendMessage(plugin.color("&3/" + label + " check <player> &7- ᴄʜᴇᴄᴋ ᴅᴇᴛᴀɪʟᴇᴅ ᴀᴄᴄᴏᴜɴᴛ ꜱᴛᴀᴛᴜꜱ"));
        sender.sendMessage(plugin.color("&3/" + label + " set <player> <type> &7- ꜱᴇᴛ ᴍᴏᴅᴇ (ᴘʀᴇᴍɪᴜᴍ, ᴄʀᴀᴄᴋᴇᴅ, ʙᴇᴅʀᴏᴄᴋ)"));
        sender.sendMessage(plugin.color("&3/" + label + " unregister <player> &7- ᴅᴇʟᴇᴛᴇ ᴘʟᴀʏᴇʀ ʀᴇɢɪꜱᴛʀᴀᴛɪᴏɴ"));
        sender.sendMessage(plugin.color("&3/" + label + " forcelogin <player> &7- ꜰᴏʀᴄᴇ-ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇ ᴏɴʟɪɴᴇ ᴘʟᴀʏᴇʀ"));
        sender.sendMessage(plugin.color("&3/" + label + " import &7- ɪᴍᴘᴏʀᴛ ᴇxɪꜱᴛɪɴɢ ᴀᴄᴄᴏᴜɴᴛꜱ ꜰʀᴏᴍ ᴀᴜᴛʜᴍᴇ ᴅᴀᴛᴀʙᴀꜱᴇ"));
        sender.sendMessage(plugin.color("&3/" + label + " reload &7- ʀᴇʟᴏᴀᴅ ᴘʟᴜɢɪɴ ᴄᴏɴꜰɪɢᴜʀᴀᴛɪᴏɴ ᴀɴᴅ ᴄᴀᴄʜᴇ"));
        sender.sendMessage(plugin.color("&8&m----------------------------------------"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("tirnue.auth.admin")) return Collections.emptyList();

        if (args.length == 1) {
            return Arrays.asList("status", "ipbind", "surge", "ratelimit", "check", "set", "unregister", "forcelogin", "import", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("ipbind") || sub.equals("surge") || sub.equals("ratelimit")) {
                return Arrays.asList("on", "off").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (sub.equals("check") || sub.equals("set") || sub.equals("unregister") || sub.equals("forcelogin")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return Arrays.asList("PREMIUM", "CRACKED", "BEDROCK").stream()
                    .filter(s -> s.startsWith(args[2].toUpperCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
