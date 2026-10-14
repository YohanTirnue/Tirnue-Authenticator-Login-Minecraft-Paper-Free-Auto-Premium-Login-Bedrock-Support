package com.tirnue.auth.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.link.GlobalPlayerLinking;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class AccountLinkManager {
    private final TirnueAuth plugin;
    private final DatabaseManager db;

    private final Map<UUID, LinkedAccount> bedrockUuidToLink = new ConcurrentHashMap<>();
    private final Map<String, LinkedAccount> bedrockNameToLink = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedAccount> javaUuidToLink = new ConcurrentHashMap<>();
    private final Map<String, LinkedAccount> javaNameToLink = new ConcurrentHashMap<>();

    public AccountLinkManager(TirnueAuth plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void initialize() {
        loadLinks();
        hookFloodgate();
    }

    public synchronized void loadLinks() {
        bedrockUuidToLink.clear();
        bedrockNameToLink.clear();
        javaUuidToLink.clear();
        javaNameToLink.clear();

        List<LinkedAccount> links = db.loadAllLinks();
        for (LinkedAccount link : links) {
            registerInMemory(link);
        }
        plugin.getLogger().info("Loaded " + links.size() + " linked player accounts from database.");
    }

    private void registerInMemory(LinkedAccount link) {
        bedrockUuidToLink.put(link.getBedrockUuid(), link);
        bedrockNameToLink.put(link.getBedrockName().toLowerCase(), link);
        javaUuidToLink.put(link.getJavaUuid(), link);
        javaNameToLink.put(link.getJavaName().toLowerCase(), link);
    }

    private void unregisterFromMemory(LinkedAccount link) {
        bedrockUuidToLink.remove(link.getBedrockUuid());
        bedrockNameToLink.remove(link.getBedrockName().toLowerCase());
        javaUuidToLink.remove(link.getJavaUuid());
        javaNameToLink.remove(link.getJavaName().toLowerCase());
    }

    public void hookFloodgate() {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            plugin.getLogger().warning("Floodgate not enabled! Bedrock account link interception will be inactive.");
            return;
        }

        try {
            PlayerLink currentLink = FloodgateApi.getInstance().getPlayerLink();
            if (currentLink instanceof GlobalPlayerLinking) {
                GlobalPlayerLinking global = (GlobalPlayerLinking) currentLink;
                Field dbField = GlobalPlayerLinking.class.getDeclaredField("databaseImpl");
                dbField.setAccessible(true);
                dbField.set(global, new TirnueFloodgateLink(this));
                plugin.getLogger().info("Successfully installed native TirnueFloodgateLink into Floodgate!");
            } else {
                plugin.getLogger().info("Floodgate PlayerLink is of type: " + (currentLink != null ? currentLink.getClass().getName() : "null"));
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to hook into Floodgate PlayerLink: " + t.getMessage(), t);
        }
    }

    public LinkedAccount getLinkByBedrockUuid(UUID uuid) {
        return uuid != null ? bedrockUuidToLink.get(uuid) : null;
    }

    public LinkedAccount getLinkByBedrockName(String name) {
        return name != null ? bedrockNameToLink.get(name.toLowerCase()) : null;
    }

    public LinkedAccount getLinkByJavaUuid(UUID uuid) {
        return uuid != null ? javaUuidToLink.get(uuid) : null;
    }

    public LinkedAccount getLinkByJavaName(String name) {
        return name != null ? javaNameToLink.get(name.toLowerCase()) : null;
    }

    public Optional<LinkedAccount> getLinkByName(String name) {
        if (name == null) return Optional.empty();
        String lower = name.toLowerCase();
        LinkedAccount link = bedrockNameToLink.get(lower);
        if (link != null) return Optional.of(link);
        link = javaNameToLink.get(lower);
        if (link != null) return Optional.of(link);
        return Optional.empty();
    }

    public boolean isLinked(UUID uuid) {
        if (uuid == null) return false;
        return bedrockUuidToLink.containsKey(uuid) || javaUuidToLink.containsKey(uuid);
    }

    public boolean isLinked(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return bedrockNameToLink.containsKey(lower) || javaNameToLink.containsKey(lower);
    }

    public List<LinkedAccount> getAllLinks() {
        return new ArrayList<>(bedrockUuidToLink.values());
    }

    public LinkResult linkAccounts(String originalName, String copyName) {
        if (originalName == null || copyName == null) {
            return new LinkResult(false, "Player names cannot be null!");
        }

        boolean origIsBedrock = isBedrockName(originalName);
        boolean copyIsBedrock = isBedrockName(copyName);

        if (origIsBedrock == copyIsBedrock) {
            return new LinkResult(false, "You can only link 1 Java account and 1 Bedrock account together! One must have a '.' prefix and one must not.");
        }

        String bedrockName = origIsBedrock ? originalName : copyName;
        String javaName = origIsBedrock ? copyName : originalName;

        if (!bedrockName.startsWith(".")) {
            bedrockName = "." + bedrockName;
        }

        LinkedAccount.MasterType masterType = origIsBedrock ? LinkedAccount.MasterType.BEDROCK : LinkedAccount.MasterType.JAVA;

        // Resolve UUIDs
        UUID bedrockUuid = resolveBedrockUuid(bedrockName);
        if (bedrockUuid == null) {
            return new LinkResult(false, "Could not resolve UUID for Bedrock player '" + bedrockName + "'. Make sure they have joined the server at least once.");
        }

        UUID javaUuid = resolveJavaUuid(javaName);
        if (javaUuid == null) {
            return new LinkResult(false, "Could not resolve UUID for Java player '" + javaName + "'.");
        }

        // Check if either is already linked
        Optional<LinkedAccount> existingBedrock = getLinkByName(bedrockName);
        if (existingBedrock.isPresent()) {
            return new LinkResult(false, "Bedrock player '" + bedrockName + "' is already linked to Java '" + existingBedrock.get().getJavaName() + "'! Unlink them first.");
        }

        Optional<LinkedAccount> existingJava = getLinkByName(javaName);
        if (existingJava.isPresent()) {
            return new LinkResult(false, "Java player '" + javaName + "' is already linked to Bedrock '" + existingJava.get().getBedrockName() + "'! Unlink them first.");
        }

        UUID masterUuid = origIsBedrock ? bedrockUuid : javaUuid;
        UUID copyUuid = origIsBedrock ? javaUuid : bedrockUuid;

        // Make safety backup of copy account's playerdata
        backupPlayerData(copyUuid, copyName);

        LinkedAccount link = new LinkedAccount(bedrockName, bedrockUuid, javaName, javaUuid, masterType, masterUuid, copyUuid, System.currentTimeMillis());

        // Save to DB and RAM
        db.saveLink(link);
        registerInMemory(link);

        // Kick copy account if online so they rejoin under master UUID
        kickOnlinePlayer(copyName, "Your account has been linked to " + originalName + "! Please rejoin.");

        return new LinkResult(true, "Successfully linked " + bedrockName + " and " + javaName + "! Master UUID: " + masterUuid + " (" + masterType.name() + ")");
    }

    public boolean unlinkAccount(String name) {
        Optional<LinkedAccount> linkOpt = getLinkByName(name);
        if (!linkOpt.isPresent()) {
            return false;
        }

        LinkedAccount link = linkOpt.get();
        db.deleteLink(link.getBedrockName());
        db.deleteLink(link.getJavaName());
        unregisterFromMemory(link);

        // Disconnect active sessions so they revert to individual profiles
        kickOnlinePlayer(link.getBedrockName(), "Your account link has been removed. Please rejoin.");
        kickOnlinePlayer(link.getJavaName(), "Your account link has been removed. Please rejoin.");

        return true;
    }

    private void kickOnlinePlayer(String name, String reason) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p != null && p.isOnline()) {
                p.kick(net.kyori.adventure.text.Component.text(reason));
            }
        });
    }

    private void backupPlayerData(UUID uuid, String name) {
        if (uuid == null) return;
        try {
            File worldDir = Bukkit.getWorldContainer();
            File playerdataDir = new File(worldDir, "world/players/data");
            if (!playerdataDir.exists()) {
                playerdataDir = new File(worldDir, "world/playerdata");
            }
            File srcFile = new File(playerdataDir, uuid.toString() + ".dat");
            if (srcFile.exists()) {
                File backupDir = new File(playerdataDir, "linked_backups");
                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                File destFile = new File(backupDir, uuid.toString() + "_" + stamp + "_" + name + ".dat");
                Files.copy(srcFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Created safety playerdata backup for " + name + " (" + uuid + ") at " + destFile.getName());
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to backup playerdata for " + name + ": " + t.getMessage(), t);
        }
    }

    private boolean isBedrockName(String name) {
        if (name == null) return false;
        if (name.startsWith(".")) return true;
        Optional<UserAccount> user = db.getUser(name);
        if (user.isPresent() && user.get().getAuthType() == UserAccount.AuthType.BEDROCK) {
            return true;
        }
        Optional<UserAccount> userDot = db.getUser("." + name);
        if (userDot.isPresent() && userDot.get().getAuthType() == UserAccount.AuthType.BEDROCK) {
            return true;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            try {
                Player p = Bukkit.getPlayerExact(name);
                if (p == null) p = Bukkit.getPlayerExact("." + name);
                if (p != null && (FloodgateApi.getInstance().isFloodgatePlayer(p.getUniqueId()) || FloodgateApi.getInstance().isFloodgateId(p.getUniqueId()))) {
                    return true;
                }
                for (org.geysermc.floodgate.api.player.FloodgatePlayer fp : FloodgateApi.getInstance().getPlayers()) {
                    if (name.equalsIgnoreCase(fp.getUsername()) || name.equalsIgnoreCase("." + fp.getUsername()) || name.equalsIgnoreCase(fp.getJavaUsername())) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private UUID resolveBedrockUuid(String bedrockName) {
        String dotted = bedrockName.startsWith(".") ? bedrockName : "." + bedrockName;
        String raw = bedrockName.startsWith(".") ? bedrockName.substring(1) : bedrockName;

        // 1. Online player
        Player p = Bukkit.getPlayerExact(dotted);
        if (p == null) p = Bukkit.getPlayerExact(raw);
        if (p != null) {
            return p.getUniqueId();
        }

        // 2. Database UserAccount
        Optional<UserAccount> user = db.getUser(dotted);
        if (!user.isPresent()) user = db.getUser(raw);
        if (user.isPresent() && user.get().getUuid() != null) {
            return user.get().getUuid();
        }

        // 3. Usercache.json scan
        UUID cachedUuid = scanUserCache(dotted);
        if (cachedUuid != null) {
            return cachedUuid;
        }

        // 4. Bukkit getOfflinePlayer
        OfflinePlayer op = Bukkit.getOfflinePlayer(dotted);
        if (op != null && op.hasPlayedBefore()) {
            return op.getUniqueId();
        }
        op = Bukkit.getOfflinePlayer(raw);
        if (op != null && op.hasPlayedBefore()) {
            return op.getUniqueId();
        }

        // 5. Query Floodgate API
        if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            try {
                java.util.concurrent.CompletableFuture<UUID> future = FloodgateApi.getInstance().getUuidFor(raw);
                if (future != null) {
                    UUID resolved = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private UUID resolveJavaUuid(String javaName) {
        // 1. Online player
        Player p = Bukkit.getPlayerExact(javaName);
        if (p != null) {
            return p.getUniqueId();
        }

        // 2. Database UserAccount
        Optional<UserAccount> user = db.getUser(javaName);
        if (user.isPresent() && user.get().getUuid() != null) {
            return user.get().getUuid();
        }

        // 3. Usercache.json scan
        UUID cachedUuid = scanUserCache(javaName);
        if (cachedUuid != null) {
            return cachedUuid;
        }

        // 4. Bukkit getOfflinePlayer
        OfflinePlayer op = Bukkit.getOfflinePlayer(javaName);
        if (op != null && op.hasPlayedBefore()) {
            return op.getUniqueId();
        }

        // 5. Offline-mode deterministic UUID
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + javaName).getBytes(StandardCharsets.UTF_8));
    }

    private UUID scanUserCache(String name) {
        if (name == null) return null;
        String raw = name.replaceFirst("^\\.", "");
        String dotted = "." + raw;
        try {
            File cacheFile = new File(Bukkit.getWorldContainer(), "usercache.json");
            if (!cacheFile.exists()) {
                cacheFile = new File("usercache.json");
            }
            if (!cacheFile.exists()) return null;

            try (FileReader reader = new FileReader(cacheFile)) {
                JsonElement elem = JsonParser.parseReader(reader);
                if (elem != null && elem.isJsonArray()) {
                    JsonArray arr = elem.getAsJsonArray();
                    for (JsonElement item : arr) {
                        if (item.isJsonObject()) {
                            JsonObject obj = item.getAsJsonObject();
                            if (obj.has("name") && obj.has("uuid")) {
                                String entryName = obj.get("name").getAsString();
                                if (entryName.equalsIgnoreCase(raw) || entryName.equalsIgnoreCase(dotted)) {
                                    return UUID.fromString(obj.get("uuid").getAsString());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static class LinkResult {
        private final boolean success;
        private final String message;

        public LinkResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}
