package com.tirnue.auth.dialog;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.mojang.MojangService;
import com.tirnue.auth.security.PasswordSecurity;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.geysermc.floodgate.api.FloodgateApi;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class PreJoinDialogManager implements Listener {
    private static final Key KEY_LOGIN_SUBMIT = Key.key("tirnueauth", "login_submit");
    private static final Key KEY_LOGIN_CANCEL = Key.key("tirnueauth", "login_cancel");
    private static final Key KEY_REGISTER_SUBMIT = Key.key("tirnueauth", "register_submit");
    private static final Key KEY_REGISTER_CANCEL = Key.key("tirnueauth", "register_cancel");
    private static final Key KEY_QUEUE_REFRESH = Key.key("tirnueauth", "queue_refresh");

    public enum DialogResultType {
        SUCCESS,
        DISCONNECT_KICK,
        ENTER_LIMBO
    }

    public static class DialogResult {
        public static final DialogResult SUCCESS = new DialogResult(DialogResultType.SUCCESS, null);
        public static final DialogResult ENTER_LIMBO = new DialogResult(DialogResultType.ENTER_LIMBO, null);
        public static final DialogResult DISCONNECTED = new DialogResult(DialogResultType.DISCONNECT_KICK, "Disconnected");

        private final DialogResultType type;
        private final String message;

        public DialogResult(DialogResultType type, String message) {
            this.type = type;
            this.message = message;
        }

        public DialogResultType getType() {
            return type;
        }

        public String getMessage() {
            return message;
        }
    }

    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;
    private final MojangService mojangService;
    private final com.tirnue.auth.security.RegistrationSurgeManager registrationSurgeManager;

    private final Map<UUID, CompletableFuture<DialogResult>> pendingDialogs = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> preJoinAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> dialogShownAt = new ConcurrentHashMap<>();
    private boolean hasFloodgate = false;

    public PreJoinDialogManager(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager, MojangService mojangService, com.tirnue.auth.security.RegistrationSurgeManager registrationSurgeManager) {
        this.plugin = plugin;
        this.db = db;
        this.sessionManager = sessionManager;
        this.mojangService = mojangService;
        this.registrationSurgeManager = registrationSurgeManager;

        if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            this.hasFloodgate = true;
        }
    }

    public boolean shouldSkipDialog(String username, UUID uuid, String clientIp) {
        if (!plugin.getConfig().getBoolean("dialog.enabled", true)) {
            return true;
        }

        // 1. Bedrock players (prefix '.' or Floodgate)
        if (username.startsWith(".")) {
            return true;
        }
        if (hasFloodgate && uuid != null) {
            try {
                if (FloodgateApi.getInstance().isFloodgateId(uuid) || FloodgateApi.getInstance().isFloodgatePlayer(uuid)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Account check: Premium vs Cracked
        Optional<UserAccount> accOpt = db.getUser(username);
        if (accOpt.isPresent()) {
            UserAccount acc = accOpt.get();
            if (acc.getAuthType() == UserAccount.AuthType.PREMIUM) {
                if (plugin.getConfig().getBoolean("mojang.enabled", true)) {
                    boolean ipBinding = plugin.getConfig().getBoolean("mojang.premium-ip-binding", true);
                    if (ipBinding) {
                        String storedIp = acc.getIp();
                        if (storedIp != null && !storedIp.isEmpty() && !storedIp.equalsIgnoreCase(clientIp)) {
                            // IP mismatch on bound premium account! Do not skip dialog (triggers premium-cracked-kick)
                            return false;
                        }
                    }
                    return true;
                }
                return false;
            } else if (acc.getAuthType() == UserAccount.AuthType.CRACKED) {
                // IP Session check strictly reserved for registered cracked accounts
                if (clientIp != null && db.isSessionValid(username, clientIp)) {
                    return true;
                }
                return false;
            }
        }

        // 3. New account: auto-detect Mojang Premium
        if (plugin.getConfig().getBoolean("mojang.enabled", true) && plugin.getConfig().getBoolean("mojang.auto-detect", true)) {
            Optional<MojangService.CachedMojangProfile> prof = mojangService.getOrFetchProfile(username);
            if (prof.isPresent() && prof.get().isPremium()) {
                return true;
            }
        }

        // 4. Fallback IP session for unclassified accounts
        if (clientIp != null && db.isSessionValid(username, clientIp)) {
            return true;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerConfigure(AsyncPlayerConnectionConfigureEvent event) {
        mojangService.recordHandshake();
        PlayerConfigurationConnection conn = event.getConnection();
        if (conn == null || conn.getProfile() == null) return;

        UUID uuid = conn.getProfile().getId();
        String name = conn.getProfile().getName();
        if (uuid == null || name == null) return;

        InetSocketAddress addr = conn.getClientAddress();
        String clientIp = addr != null ? addr.getAddress().getHostAddress() : "127.0.0.1";

        if (shouldSkipDialog(name, uuid, clientIp)) {
            // Player skips the configuration dialog (Mojang Premium, Bedrock, or valid session).
            // Let them proceed directly into the game where PlayerProtectionListener or BedrockAuthListener
            // will auto-authenticate them with the proper mojang-welcome message.
            return;
        }

        // STRICT PREMIUM CHECK:
        // Accounts registered as PREMIUM must NEVER be shown a password/login dialog!
        // Any cracked/unverified login attempts on a premium username are kicked immediately!
        Optional<UserAccount> accOpt = db.getUser(name);
        if (accOpt.isPresent() && accOpt.get().getAuthType() == UserAccount.AuthType.PREMIUM) {
            String kickMsg = plugin.getMessage("premium-cracked-kick", "ꑬ &cᴛʜɪꜱ ᴀᴄᴄᴏᴜɴᴛ ɪꜱ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴀꜱ ᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴘʀᴇᴍɪᴜᴍ. ᴄʀᴀᴄᴋᴇᴅ ʟᴏɢɪɴꜱ ᴀʀᴇ ɴᴏᴛ ᴘᴇʀᴍɪᴛᴛᴇᴅ.");
            conn.disconnect(Component.text(plugin.stripColor(plugin.color(kickMsg))));
            return;
        }

        boolean registered = db.isRegistered(name);
        Dialog dialog;
        if (registered) {
            dialog = createLoginDialog(name);
        } else if (registrationSurgeManager.isSurgeActive() && !registrationSurgeManager.canRegister(uuid)) {
            int pos = registrationSurgeManager.addToQueue(uuid);
            int estSec = registrationSurgeManager.getEstimatedWaitSeconds(uuid);
            dialog = createQueueDialog(name, pos, estSec);
        } else {
            dialog = createRegisterDialog(name);
        }

        CompletableFuture<DialogResult> future = new CompletableFuture<>();
        int timeoutSec = plugin.getConfig().getInt("security.login-timeout-seconds", 120);
        future.completeOnTimeout(new DialogResult(DialogResultType.DISCONNECT_KICK,
                plugin.getMessage("timeout-kick", "&cᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛɪᴏɴ ᴛɪᴍᴇᴅ ᴏᴜᴛ. ᴘʟᴇᴀꜱᴇ ʟᴏɢ ɪɴ ᴘʀᴏᴍᴘᴛʟʏ ᴜᴘᴏɴ ᴊᴏɪɴɪɴɢ.")),
                Math.max(5, timeoutSec), TimeUnit.SECONDS);

        pendingDialogs.put(uuid, future);
        dialogShownAt.put(uuid, System.currentTimeMillis());

        try {
            conn.getAudience().showDialog(dialog);
            DialogResult result = future.join();
            conn.getAudience().closeDialog();

            pendingDialogs.remove(uuid);
            preJoinAttempts.remove(uuid);
            dialogShownAt.remove(uuid);
            registrationSurgeManager.removeFromQueue(uuid);

            if (result == null || result.getType() != DialogResultType.SUCCESS) {
                String kickMsg = (result != null && result.getMessage() != null && !result.getMessage().isEmpty() && !result.getMessage().equals("Disconnected"))
                        ? result.getMessage()
                        : plugin.getMessage("dialog-required-kick", "ꑬ &cʏᴏᴜ ᴍᴜꜱᴛ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇ ᴠɪᴀ ᴛʜᴇ ʟᴏɢɪɴ ᴅɪᴀʟᴏɢ.");
                conn.disconnect(Component.text(plugin.stripColor(plugin.color(kickMsg))));
                return;
            }

            sessionManager.markPreAuthenticated(uuid);
        } catch (Throwable t) {
            pendingDialogs.remove(uuid);
            preJoinAttempts.remove(uuid);
            dialogShownAt.remove(uuid);
            registrationSurgeManager.removeFromQueue(uuid);
            plugin.getLogger().warning("Error handling pre-join dialog for " + name + ": " + t.getMessage());
            try {
                conn.disconnect(Component.text(plugin.stripColor(plugin.color(
                        plugin.getMessage("dialog-required-kick", "ꑬ &cʏᴏᴜ ᴍᴜꜱᴛ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇ ᴠɪᴀ ᴛʜᴇ ʟᴏɢɪɴ ᴅɪᴀʟᴏɢ.")))));
            } catch (Throwable ignored) {
            }
        }
    }

    @EventHandler
    public void onPlayerConnectionClose(PlayerConnectionCloseEvent event) {
        UUID uuid = event.getPlayerUniqueId();
        CompletableFuture<DialogResult> future = pendingDialogs.remove(uuid);
        if (future != null && !future.isDone()) {
            future.complete(DialogResult.DISCONNECTED);
        }
        preJoinAttempts.remove(uuid);
        dialogShownAt.remove(uuid);
        registrationSurgeManager.removeFromQueue(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCustomClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection conn)) {
            return;
        }

        if (conn.getProfile() == null) return;
        UUID uuid = conn.getProfile().getId();
        String name = conn.getProfile().getName();
        if (uuid == null || name == null) return;

        CompletableFuture<DialogResult> future = pendingDialogs.get(uuid);
        if (future == null || future.isDone()) return;

        Key id = event.getIdentifier();
        DialogResponseView resp = event.getDialogResponseView();

        try {
            if (KEY_LOGIN_CANCEL.equals(id)) {
                String cancelMsg = plugin.getMessage("login-cancelled-kick", "ꑬ &cʟᴏɢɪɴ ᴄᴀɴᴄᴇʟʟᴇᴅ. ᴘʟᴇᴀꜱᴇ ʀᴇᴄᴏɴɴᴇᴄᴛ ᴛᴏ ʟᴏɢ ɪɴ.");
                future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK, cancelMsg));
                return;
            }

            if (KEY_REGISTER_CANCEL.equals(id)) {
                String cancelMsg = plugin.getMessage("register-cancelled-kick", "ꑬ &cʀᴇɢɪꜱᴛʀᴀᴛɪᴏɴ ᴄᴀɴᴄᴇʟʟᴇᴅ. ᴘʟᴇᴀꜱᴇ ʀᴇᴄᴏɴɴᴇᴄᴛ ᴛᴏ ʀᴇɢɪꜱᴛᴇʀ.");
                future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK, cancelMsg));
                return;
            }

            if (KEY_QUEUE_REFRESH.equals(id)) {
                if (!registrationSurgeManager.isSurgeActive() || registrationSurgeManager.canRegister(uuid)) {
                    dialogShownAt.put(uuid, System.currentTimeMillis());
                    conn.getAudience().showDialog(createRegisterDialog(name));
                } else {
                    int pos = registrationSurgeManager.getQueuePosition(uuid);
                    int estSec = registrationSurgeManager.getEstimatedWaitSeconds(uuid);
                    conn.getAudience().showDialog(createQueueDialog(name, pos, estSec));
                }
                return;
            }

            if (KEY_LOGIN_SUBMIT.equals(id)) {
                String password = resp != null ? resp.getText("password") : null;
                if (password == null || password.isEmpty()) {
                    future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                            plugin.getMessage("wrong-password", "{prefix}&cɪɴᴄᴏʀʀᴇᴄᴛ ᴘᴀꜱꜱᴡᴏʀᴅ! ᴀᴛᴛᴇᴍᴘᴛꜱ ʀᴇᴍᴀɪɴɪɴɢ: &f0",
                                    "{remaining}", "0")));
                    return;
                }

                Optional<UserAccount> userOpt = db.getUser(name);
                if (!userOpt.isPresent()) {
                    future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                            plugin.getMessage("not-registered", "{prefix}&cᴛʜɪꜱ ᴜꜱᴇʀɴᴀᴍᴇ ɪꜱ ɴᴏᴛ ʀᴇɢɪꜱᴛᴇʀᴇᴅ!")));
                    return;
                }

                UserAccount user = userOpt.get();
                if (user.getAuthType() == UserAccount.AuthType.PREMIUM || "$PREMIUM$".equals(user.getPasswordHash()) || "$".equals(user.getPasswordHash())) {
                    future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                            plugin.getMessage("premium-cracked-kick",
                                    "ꑬ &cᴛʜɪꜱ ᴀᴄᴄᴏᴜɴᴛ ɪꜱ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴀꜱ ᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴘʀᴇᴍɪᴜᴍ. ᴄʀᴀᴄᴋᴇᴅ ʟᴏɢɪɴꜱ ᴀʀᴇ ɴᴏᴛ ᴘᴇʀᴍɪᴛᴛᴇᴅ.")));
                    return;
                }

                if (PasswordSecurity.checkPassword(password, user.getPasswordHash())) {
                    // Correct password!
                    InetSocketAddress clientSock = conn.getClientAddress();
                    if (clientSock != null && clientSock.getAddress() != null) {
                        user.setIp(clientSock.getAddress().getHostAddress());
                        user.setLastLogin(System.currentTimeMillis());
                        db.saveUser(user);
                    }
                    future.complete(DialogResult.SUCCESS);
                } else {
                    int attempts = preJoinAttempts.getOrDefault(uuid, 0) + 1;
                    preJoinAttempts.put(uuid, attempts);
                    int maxAttempts = plugin.getConfig().getInt("security.max-failed-attempts", 5);

                    if (attempts >= maxAttempts) {
                        future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                                plugin.getMessage("max-attempts-kick", "&cᴛᴏᴏ ᴍᴀɴʏ ꜰᴀɪʟᴇᴅ ʟᴏɢɪɴ ᴀᴛᴛᴇᴍᴘᴛꜱ.")));
                    } else {
                        int remaining = maxAttempts - attempts;
                        String wrongMsg = plugin.getMessage("wrong-password", "{prefix}&cɪɴᴄᴏʀʀᴇᴄᴛ ᴘᴀꜱꜱᴡᴏʀᴅ! ᴀᴛᴛᴇᴍᴘᴛꜱ ʀᴇᴍᴀɪɴɪɴɢ: &f{remaining}",
                                "{remaining}", String.valueOf(remaining));
                        future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK, wrongMsg));
                    }
                }
                return;
            }

            if (KEY_REGISTER_SUBMIT.equals(id)) {
                Long shownTime = dialogShownAt.get(uuid);
                long elapsed = shownTime != null ? (System.currentTimeMillis() - shownTime) : 5000L;
                long minReaction = registrationSurgeManager.getMinHumanReactionMs();
                if (elapsed < minReaction) {
                    future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                            plugin.getMessage("bot-reaction-kick",
                                    "ꑬ &cᴀᴜᴛᴏᴍᴀᴛᴇᴅ ꜱᴜʙᴍɪꜱꜱɪᴏɴ ᴅᴇᴛᴇᴄᴛᴇᴅ ({ms}ᴍꜱ). ᴘʟᴇᴀꜱᴇ ᴛʏᴘᴇ ᴍᴀɴᴜᴀʟʟʏ.",
                                    "{ms}", String.valueOf(elapsed))));
                    return;
                }

                if (registrationSurgeManager.isSurgeActive() && !registrationSurgeManager.canRegister(uuid)) {
                    int pos = registrationSurgeManager.getQueuePosition(uuid);
                    int estSec = registrationSurgeManager.getEstimatedWaitSeconds(uuid);
                    conn.getAudience().showDialog(createQueueDialog(name, pos, estSec));
                    return;
                }

                String password = resp != null ? resp.getText("password") : null;
                String confirm = resp != null ? resp.getText("confirmPassword") : null;

                int minLen = plugin.getConfig().getInt("security.min-password-length", 4);
                int maxLen = plugin.getConfig().getInt("security.max-password-length", 64);

                if (password == null || password.length() < minLen) {
                    future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                            plugin.getMessage("password-too-short", "{prefix}&cᴘᴀꜱꜱᴡᴏʀᴅ ɪꜱ ᴛᴏᴏ ꜱʜᴏʀᴛ. ᴍɪɴɪᴍᴜᴍ ʟᴇɴɢᴛʜ ɪꜱ &f{min} &cᴄʜᴀʀᴀᴄᴛᴇʀꜱ.",
                                    "{min}", String.valueOf(minLen))));
                    return;
                }

                if (password.length() > maxLen) {
                    future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                            plugin.getMessage("password-too-long", "{prefix}&cᴘᴀꜱꜱᴡᴏʀᴅ ᴇxᴄᴇᴇᴅꜱ ᴍᴀxɪᴍᴜᴍ ʟᴇɴɢᴛʜ ᴏꜰ &f{max} &cᴄʜᴀʀᴀᴄᴛᴇʀꜱ.",
                                    "{max}", String.valueOf(maxLen))));
                    return;
                }

                if (confirm != null && !password.equals(confirm)) {
                    future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                            plugin.getMessage("passwords-dont-match", "{prefix}&cᴛʜᴇ ᴛᴡᴏ ᴘᴀꜱꜱᴡᴏʀᴅꜱ ᴇɴᴛᴇʀᴇᴅ ᴅᴏ ɴᴏᴛ ᴍᴀᴛᴄʜ.")));
                    return;
                }

                InetSocketAddress addr = conn.getClientAddress();
                String clientIp = addr != null ? addr.getAddress().getHostAddress() : "127.0.0.1";
                int maxPerIp = plugin.getConfig().getInt("security.max-registrations-per-ip", 4);
                if (maxPerIp > 0 && db.countRegistrationsByIp(clientIp) >= maxPerIp) {
                    future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                            plugin.getMessage("max-accounts-exceeded", "{prefix}&cᴍᴀxɪᴍᴜᴍ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴀᴄᴄᴏᴜɴᴛꜱ ᴘᴇʀ ɪᴘ ᴀᴅᴅʀᴇꜱꜱ ʀᴇᴀᴄʜᴇᴅ (&f{max}&c).",
                                    "{max}", String.valueOf(maxPerIp))));
                    return;
                }

                // Create account
                String hash = PasswordSecurity.hashPassword(password);
                UserAccount account = new UserAccount(
                        name,
                        uuid,
                        hash,
                        clientIp,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        UserAccount.AuthType.CRACKED,
                        false
                );
                db.saveUser(account);
                registrationSurgeManager.removeFromQueue(uuid);
                registrationSurgeManager.recordRegistration();
                future.complete(DialogResult.SUCCESS);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error processing dialog response for " + name, t);
            future.complete(new DialogResult(DialogResultType.DISCONNECT_KICK,
                    plugin.getMessage("dialog-required-kick", "ꑬ &cʏᴏᴜ ᴍᴜꜱᴛ ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛᴇ ᴠɪᴀ ᴛʜᴇ ʟᴏɢɪɴ ᴅɪᴀʟᴏɢ.")));
        }
    }

    public Dialog createLoginDialog(String username) {
        String titleStr = plugin.getConfig().getString("dialog.login.title", "&#55cdfcʟᴏɢɪɴ");
        String bodyStr = plugin.getConfig().getString("dialog.login.body", "&7ᴘʟᴇᴀꜱᴇ ᴇɴᴛᴇʀ ʏᴏᴜʀ ᴘᴀꜱꜱᴡᴏʀᴅ ᴛᴏ ᴄᴏɴᴛɪɴᴜᴇ.");
        String passLabel = plugin.getConfig().getString("dialog.login.password-label", "ᴘᴀꜱꜱᴡᴏʀᴅ");
        String submitBtn = plugin.getConfig().getString("dialog.login.submit-button", "&aʟᴏɢɪɴ");
        String cancelBtn = plugin.getConfig().getString("dialog.login.cancel-button", "&cᴄᴀɴᴄᴇʟ");
        boolean allowEscape = plugin.getConfig().getBoolean("dialog.allow-close-with-escape", false);

        Component title = LegacyComponentSerializer.legacySection().deserialize(plugin.color(titleStr));
        List<DialogBody> body = Collections.singletonList(
                DialogBody.plainMessage(LegacyComponentSerializer.legacySection().deserialize(plugin.color(bodyStr)))
        );

        List<DialogInput> inputs = Collections.singletonList(
                DialogInput.text("password", LegacyComponentSerializer.legacySection().deserialize(plugin.color(passLabel)))
                        .maxLength(plugin.getConfig().getInt("security.max-password-length", 64))
                        .build()
        );

        DialogBase base = DialogBase.builder(title)
                .body(body)
                .inputs(inputs)
                .canCloseWithEscape(allowEscape)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .build();

        List<ActionButton> buttons = Arrays.asList(
                ActionButton.builder(LegacyComponentSerializer.legacySection().deserialize(plugin.color(submitBtn)))
                        .action(DialogAction.customClick(KEY_LOGIN_SUBMIT, null))
                        .build(),
                ActionButton.builder(LegacyComponentSerializer.legacySection().deserialize(plugin.color(cancelBtn)))
                        .action(DialogAction.customClick(KEY_LOGIN_CANCEL, null))
                        .build()
        );

        return Dialog.create(factory -> {
            factory.empty()
                    .base(base)
                    .type(DialogType.multiAction(buttons).build());
        });
    }

    public Dialog createRegisterDialog(String username) {
        String titleStr = plugin.getConfig().getString("dialog.register.title", "&#55cdfcʀᴇɢɪꜱᴛᴇʀ");
        String bodyStr = plugin.getConfig().getString("dialog.register.body", "&7ᴄʀᴇᴀᴛᴇ ᴀɴ ᴀᴄᴄᴏᴜɴᴛ ᴛᴏ ꜱᴛᴀʀᴛ ᴘʟᴀʏɪɴɢ ᴏɴ ᴛʜɪꜱ ꜱᴇʀᴠᴇʀ.");
        String passLabel = plugin.getConfig().getString("dialog.register.password-label", "ᴘᴀꜱꜱᴡᴏʀᴅ");
        String confirmLabel = plugin.getConfig().getString("dialog.register.confirm-label", "ᴄᴏɴꜰɪʀᴍ ᴘᴀꜱꜱᴡᴏʀᴅ");
        String submitBtn = plugin.getConfig().getString("dialog.register.submit-button", "&aʀᴇɢɪꜱᴛᴇʀ");
        String cancelBtn = plugin.getConfig().getString("dialog.register.cancel-button", "&cᴄᴀɴᴄᴇʟ");
        boolean allowEscape = plugin.getConfig().getBoolean("dialog.allow-close-with-escape", false);

        Component title = LegacyComponentSerializer.legacySection().deserialize(plugin.color(titleStr));
        List<DialogBody> body = Collections.singletonList(
                DialogBody.plainMessage(LegacyComponentSerializer.legacySection().deserialize(plugin.color(bodyStr)))
        );

        List<DialogInput> inputs = Arrays.asList(
                DialogInput.text("password", LegacyComponentSerializer.legacySection().deserialize(plugin.color(passLabel)))
                        .maxLength(plugin.getConfig().getInt("security.max-password-length", 64))
                        .build(),
                DialogInput.text("confirmPassword", LegacyComponentSerializer.legacySection().deserialize(plugin.color(confirmLabel)))
                        .maxLength(plugin.getConfig().getInt("security.max-password-length", 64))
                        .build()
        );

        DialogBase base = DialogBase.builder(title)
                .body(body)
                .inputs(inputs)
                .canCloseWithEscape(allowEscape)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .build();

        List<ActionButton> buttons = Arrays.asList(
                ActionButton.builder(LegacyComponentSerializer.legacySection().deserialize(plugin.color(submitBtn)))
                        .action(DialogAction.customClick(KEY_REGISTER_SUBMIT, null))
                        .build(),
                ActionButton.builder(LegacyComponentSerializer.legacySection().deserialize(plugin.color(cancelBtn)))
                        .action(DialogAction.customClick(KEY_REGISTER_CANCEL, null))
                        .build()
        );

        return Dialog.create(factory -> {
            factory.empty()
                    .base(base)
                    .type(DialogType.multiAction(buttons).build());
        });
    }

    public Dialog createQueueDialog(String username, int position, int waitSeconds) {
        String titleStr = plugin.getConfig().getString("dialog.queue.title", "&#55cdfcqᴜᴇᴜᴇ");
        int minutes = Math.max(1, (waitSeconds + 59) / 60);
        String bodyStr = plugin.getConfig().getString("dialog.queue.body",
                "&eʜɪ! ʏᴏᴜ ᴀʀᴇ ɴᴇxᴛ ɪɴ ʟɪɴᴇ ɪɴ qᴜᴇᴜᴇ ꜰᴏʀ ʀᴇɢɪꜱᴛʀᴀᴛɪᴏɴ.\n\n" +
                "&7• ʏᴏᴜʀ ᴘᴏꜱɪᴛɪᴏɴ: &f#{pos}\n" +
                "&7• ᴇꜱᴛɪᴍᴀᴛᴇᴅ ᴡᴀɪᴛ: &b~{min} ᴍɪɴᴜᴛᴇꜱ\n\n" +
                "&8(ᴘʟᴇᴀꜱᴇ ᴡᴀɪᴛ ᴀ ᴍᴏᴍᴇɴᴛ ᴡʜɪʟᴇ ᴡᴇ ᴘʀᴇᴘᴀʀᴇ ʏᴏᴜʀ ꜱᴇꜱꜱɪᴏɴ)")
                .replace("{pos}", String.valueOf(position))
                .replace("{min}", String.valueOf(minutes))
                .replace("{sec}", String.valueOf(waitSeconds));

        String refreshBtn = plugin.getConfig().getString("dialog.queue.refresh-button", "&eᴄʜᴇᴄᴋ qᴜᴇᴜᴇ / ʀᴇꜰʀᴇꜱʜ");
        String cancelBtn = plugin.getConfig().getString("dialog.queue.cancel-button", "&cᴄᴀɴᴄᴇʟ");

        Component title = LegacyComponentSerializer.legacySection().deserialize(plugin.color(titleStr));
        List<DialogBody> body = Collections.singletonList(
                DialogBody.plainMessage(LegacyComponentSerializer.legacySection().deserialize(plugin.color(bodyStr)))
        );

        DialogBase base = DialogBase.builder(title)
                .body(body)
                .inputs(Collections.emptyList())
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .build();

        List<ActionButton> buttons = Arrays.asList(
                ActionButton.builder(LegacyComponentSerializer.legacySection().deserialize(plugin.color(refreshBtn)))
                        .action(DialogAction.customClick(KEY_QUEUE_REFRESH, null))
                        .build(),
                ActionButton.builder(LegacyComponentSerializer.legacySection().deserialize(plugin.color(cancelBtn)))
                        .action(DialogAction.customClick(KEY_REGISTER_CANCEL, null))
                        .build()
        );

        return Dialog.create(factory -> {
            factory.empty()
                    .base(base)
                    .type(DialogType.multiAction(buttons).build());
        });
    }
}
