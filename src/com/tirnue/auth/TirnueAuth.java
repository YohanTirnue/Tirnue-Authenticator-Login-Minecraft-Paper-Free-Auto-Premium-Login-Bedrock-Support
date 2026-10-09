package com.tirnue.auth;

import com.tirnue.auth.command.*;
import com.tirnue.auth.dialog.PreJoinDialogManager;
import com.tirnue.auth.hook.PlaceholderHook;
import com.tirnue.auth.listener.BedrockAuthListener;
import com.tirnue.auth.listener.PlayerProtectionListener;
import com.tirnue.auth.mojang.MojangService;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.AuthMeImporter;
import com.tirnue.auth.storage.DatabaseManager;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TirnueAuth extends JavaPlugin {
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([a-fA-F0-9]{6})");

    private DatabaseManager databaseManager;
    private SessionManager sessionManager;
    private MojangService mojangService;
    private AuthMeImporter authMeImporter;
    private PreJoinDialogManager preJoinDialogManager;
    private org.bukkit.configuration.file.FileConfiguration messagesConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();

        // 1. Initialize SQLite Database
        databaseManager = new DatabaseManager(getDataFolder(), getLogger());
        try {
            databaseManager.initialize();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite database!", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Initialize Services
        try {
            Class.forName(com.tirnue.auth.security.PasswordSecurity.class.getName());
        } catch (Throwable ignored) {}

        sessionManager = new SessionManager(this, databaseManager);
        long cacheHours = getConfig().getLong("mojang.cache-expiry-hours", 72);
        mojangService = new MojangService(databaseManager, getLogger(), cacheHours);
        authMeImporter = new AuthMeImporter(databaseManager, getLogger(), getDataFolder());

        // 3. Auto-import from AuthMe if first run
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            authMeImporter.importFromAuthMe(false);
        });

        // 4. Register Listeners
        BedrockAuthListener bedrockListener = new BedrockAuthListener(this, databaseManager, sessionManager);
        getServer().getPluginManager().registerEvents(bedrockListener, this);

        PlayerProtectionListener protectionListener = new PlayerProtectionListener(this, databaseManager, sessionManager, mojangService);
        getServer().getPluginManager().registerEvents(protectionListener, this);

        preJoinDialogManager = new PreJoinDialogManager(this, databaseManager, sessionManager, mojangService);
        getServer().getPluginManager().registerEvents(preJoinDialogManager, this);

        // 5. Register Commands
        if (getCommand("login") != null) {
            getCommand("login").setExecutor(new LoginCommand(this, databaseManager, sessionManager));
        }
        if (getCommand("register") != null) {
            getCommand("register").setExecutor(new RegisterCommand(this, databaseManager, sessionManager));
        }
        if (getCommand("changepassword") != null) {
            getCommand("changepassword").setExecutor(new ChangePasswordCommand(this, databaseManager, sessionManager));
        }
        if (getCommand("logout") != null) {
            getCommand("logout").setExecutor(new LogoutCommand(this, databaseManager, sessionManager));
        } else {
            registerDynamicLogout();
        }

        PremiumToggleCommand premiumToggle = new PremiumToggleCommand(this, databaseManager, sessionManager, mojangService);
        if (getCommand("premium") != null) {
            getCommand("premium").setExecutor(premiumToggle);
        }
        if (getCommand("cracked") != null) {
            getCommand("cracked").setExecutor(premiumToggle);
        }

        AdminAuthCommand adminCommand = new AdminAuthCommand(this, databaseManager, sessionManager, mojangService, authMeImporter);
        if (getCommand("tauth") != null) {
            getCommand("tauth").setExecutor(adminCommand);
            getCommand("tauth").setTabCompleter(adminCommand);
        }

        // 6. PlaceholderAPI Expansion
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PlaceholderHook(this, databaseManager, sessionManager).register();
            getLogger().info("Successfully registered TirnueAuth PlaceholderAPI expansion!");
        }

        getLogger().info("TirnueAuth v" + getDescription().getVersion() + " has been successfully enabled!");
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            sessionManager.reload();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("TirnueAuth disabled.");
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public MojangService getMojangService() {
        return mojangService;
    }

    public String getPrefix() {
        return getConfig().getString("prefix", "");
    }

    public void loadMessages() {
        java.io.File dataDir = getDataFolder();
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        java.io.File engConfig = new java.io.File(dataDir, "englishlanguage.config");
        java.io.File msgFile = new java.io.File(dataDir, "messages_en.yml");

        if (engConfig.exists()) {
            messagesConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(engConfig);
        } else if (msgFile.exists()) {
            messagesConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(msgFile);
        } else {
            try {
                saveResource("messages_en.yml", false);
                messagesConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(msgFile);
            } catch (Throwable ignored) {
            }
        }
    }

    public String getRawMsg(String key, String def) {
        if (messagesConfig != null) {
            String val = messagesConfig.getString(key);
            if (val == null) val = messagesConfig.getString("messages." + key);
            if (val != null) return val;
        }
        return getConfig().getString("messages." + key, def);
    }

    public String getMessage(String key, String def, Object... replacements) {
        String raw = null;
        if (messagesConfig != null) {
            raw = messagesConfig.getString(key);
            if (raw == null) {
                raw = messagesConfig.getString("messages." + key);
            }
        }
        if (raw == null) {
            raw = getConfig().getString("messages." + key, def);
        }
        if (raw == null || raw.isEmpty()) return "";
        String prefix = getPrefix();
        String result = raw.replace("{prefix}", prefix).replace("%prefix%", prefix);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String k = String.valueOf(replacements[i]);
            String v = String.valueOf(replacements[i + 1]);
            result = result.replace(k, v);
        }
        return color(result);
    }

    public void sendMessage(org.bukkit.command.CommandSender sender, String key, String def, Object... replacements) {
        if (sender == null) return;
        String formatted = getMessage(key, def, replacements);
        if (!formatted.isEmpty()) {
            sender.sendMessage(formatted);
        }
    }

    public String getMsg(String key) {
        return getMessage(key, "");
    }

    public String color(String text) {
        if (text == null) return "";
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(buffer, ChatColor.of("#" + hex).toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public String stripColor(String text) {
        if (text == null) return "";
        return ChatColor.stripColor(text);
    }

    public void registerDynamicLogout() {
        try {
            java.lang.reflect.Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            org.bukkit.command.CommandMap commandMap = (org.bukkit.command.CommandMap) commandMapField.get(Bukkit.getServer());

            org.bukkit.command.Command existing = commandMap.getCommand("logout");
            if (existing == null) {
                org.bukkit.command.Command logoutCmd = new org.bukkit.command.Command("logout", "Log out of your current session", "/logout", java.util.Arrays.asList("unlog")) {
                    @Override
                    public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                        return new LogoutCommand(TirnueAuth.this, databaseManager, sessionManager).onCommand(sender, this, commandLabel, args);
                    }
                };
                logoutCmd.setPermission("tirnue.auth.user");
                commandMap.register("tirnueauth", logoutCmd);
                getLogger().info("Dynamically registered /logout command in Bukkit CommandMap!");
            }
        } catch (Throwable t) {
            getLogger().log(Level.FINE, "Dynamic logout registration: " + t.getMessage());
        }
    }
}
