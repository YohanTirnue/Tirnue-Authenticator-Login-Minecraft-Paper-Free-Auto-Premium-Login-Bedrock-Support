package com.tirnue.auth.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.mojang.MojangService;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import io.netty.channel.ChannelPipeline;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PacketEvents listener that intercepts the login handshake and enforces official Mojang
 * cryptographic challenge-response authentication for premium accounts while allowing cracked
 * players without premium names to proceed to in-game/dialog authentication.
 */
public class LoginEncryptionPacketListener extends PacketListenerAbstract {
    private static final Pattern VALID_USERNAME = Pattern.compile("^[a-zA-Z0-9_]{2,16}$");

    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final MojangService mojangService;
    private final MojangSessionVerifier loginVerifier;

    public LoginEncryptionPacketListener(TirnueAuth plugin, DatabaseManager db, MojangService mojangService, MojangSessionVerifier loginVerifier) {
        this.plugin = plugin;
        this.db = db;
        this.mojangService = mojangService;
        this.loginVerifier = loginVerifier;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    private void handleLoginStart(PacketReceiveEvent event) {
        WrapperLoginClientLoginStart wrapper = new WrapperLoginClientLoginStart(event);
        String username = wrapper.getUsername();
        if (username == null || !VALID_USERNAME.matcher(username).matches()) {
            return;
        }

        // Bedrock players (prefix '.') bypass Mojang Java encryption
        if (username.startsWith(".")) {
            return;
        }

        User user = event.getUser();
        ClientVersion clientVersion = user.getClientVersion();
        String connectionKey = connectionKey(user);
        UUID playerUUID = wrapper.getPlayerUUID().orElse(null);

        // Cancel LOGIN_START so server does not proceed until challenge is evaluated
        event.setCancelled(true);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean shouldChallenge = false;
            Optional<UserAccount> accOpt = db.getUser(username);

            if (accOpt.isPresent() && accOpt.get().getAuthType() == UserAccount.AuthType.PREMIUM) {
                // If registered as PREMIUM in database, cryptographic Mojang auth is STRICTLY required
                shouldChallenge = true;
            } else {
                // Not registered as PREMIUM (unregistered or registered as CRACKED):
                // Only challenge if the connecting client presents the official Mojang UUID
                if (plugin.getConfig().getBoolean("mojang.enabled", true) && plugin.getConfig().getBoolean("mojang.auto-detect", true)) {
                    Optional<MojangService.CachedMojangProfile> prof = mojangService.getOrFetchProfile(username);
                    if (prof.isPresent() && prof.get().isPremium()) {
                        UUID officialUuid = prof.get().getUuid();
                        if (officialUuid != null && officialUuid.equals(playerUUID)) {
                            shouldChallenge = true;
                        }
                    }
                }
            }

            plugin.getLogger().info("Login evaluation for '" + username + "': clientUUID=" + playerUUID
                    + ", registered=" + accOpt.isPresent() + ", challenge=" + shouldChallenge);

            if (shouldChallenge) {
                byte[] verifyToken = loginVerifier.startVerification(connectionKey, username, playerUUID);
                WrapperLoginServerEncryptionRequest encReq = new WrapperLoginServerEncryptionRequest(
                        "", loginVerifier.getPublicKey(), verifyToken, true
                );
                user.sendPacket(encReq);
            } else {
                // Cracked or unverified account: resume normal offline login flow
                resumeLogin(user, username, clientVersion, playerUUID);
            }
        });
    }

    private void handleEncryptionResponse(PacketReceiveEvent event) {
        User user = event.getUser();
        String connectionKey = connectionKey(user);
        if (!loginVerifier.hasPending(connectionKey)) {
            return;
        }

        String username = loginVerifier.getPendingUsername(connectionKey);
        UUID playerUUID = loginVerifier.getPendingPlayerUUID(connectionKey);
        ClientVersion clientVersion = user.getClientVersion();

        WrapperLoginClientEncryptionResponse wrapper = new WrapperLoginClientEncryptionResponse(event);
        event.setCancelled(true);

        Optional<byte[]> encVerifyTokenOpt = wrapper.getEncryptedVerifyToken();
        if (!encVerifyTokenOpt.isPresent()) {
            plugin.getLogger().warning("Client '" + username + "' sent invalid encryption response (missing verify token). Kicking!");
            loginVerifier.cleanupPending(connectionKey);
            kickCracked(user, username);
            return;
        }

        byte[] encSharedSecret = wrapper.getEncryptedSharedSecret().clone();
        byte[] encVerifyToken = encVerifyTokenOpt.get().clone();
        byte[] sharedSecret;

        try {
            sharedSecret = loginVerifier.decryptData(encSharedSecret);
        } catch (GeneralSecurityException e) {
            plugin.getLogger().warning("RSA decryption failed for '" + username + "': " + e.getMessage() + ". Kicking!");
            loginVerifier.cleanupPending(connectionKey);
            kickCracked(user, username);
            return;
        }

        // Install AES/CFB8 Netty ciphers on connection pipeline
        enableChannelEncryption(user.getChannel(), sharedSecret);

        loginVerifier.completeVerification(connectionKey, sharedSecret, encVerifyToken)
                .thenAccept(maybeUuid -> {
                    if (maybeUuid.isPresent()) {
                        UUID mojangUuid = maybeUuid.get();
                        loginVerifier.storeVerified(username, mojangUuid);
                        plugin.getLogger().info("Cryptographic Mojang verification succeeded for '" + username + "' (" + mojangUuid + ")!");
                        resumeLogin(user, username, clientVersion, playerUUID);
                    } else {
                        plugin.getLogger().warning("Mojang session verification FAILED for '" + username + "'! Impostor cracked client rejected.");
                        kickCracked(user, username);
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Exception during Mojang verification for '" + username + "': " + ex.getMessage());
                    kickCracked(user, username);
                    return null;
                });
    }

    private void enableChannelEncryption(Object channel, byte[] sharedSecret) {
        try {
            SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
            IvParameterSpec iv = new IvParameterSpec(sharedSecret);

            Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            decryptCipher.init(Cipher.DECRYPT_MODE, key, iv);

            Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            encryptCipher.init(Cipher.ENCRYPT_MODE, key, iv);

            ChannelPipeline pipeline = (ChannelPipeline) ChannelHelper.getPipeline(channel);
            if (pipeline.get("splitter") != null) {
                pipeline.addBefore("splitter", "decrypt", new AesCfb8Decoder(decryptCipher));
            } else {
                pipeline.addFirst("decrypt", new AesCfb8Decoder(decryptCipher));
            }

            if (pipeline.get("prepender") != null) {
                pipeline.addBefore("prepender", "encrypt", new AesCfb8Encoder(encryptCipher));
            } else {
                pipeline.addFirst("encrypt", new AesCfb8Encoder(encryptCipher));
            }
        } catch (GeneralSecurityException e) {
            plugin.getLogger().warning("Failed to install AES cipher handlers: " + e.getMessage());
        }
    }

    private void resumeLogin(User user, String username, ClientVersion clientVersion, UUID playerUUID) {
        WrapperLoginClientLoginStart resumePacket = new WrapperLoginClientLoginStart(clientVersion, username, null, playerUUID);
        user.receivePacketSilently(resumePacket);
    }

    private void kickCracked(User user, String username) {
        Optional<UserAccount> acc = db.getUser(username);
        boolean isDbPremium = acc.isPresent() && acc.get().getAuthType() == UserAccount.AuthType.PREMIUM;
        String msgKey = isDbPremium ? "premium-cracked-kick" : "premium-username-kick";
        String defaultMsg = isDbPremium
                ? "ꑬ &cᴛʜɪꜱ ᴀᴄᴄᴏᴜɴᴛ ɪꜱ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴀꜱ ᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴘʀᴇᴍɪᴜᴍ. ᴄʀᴀᴄᴋᴇᴅ ʟᴏɢɪɴꜱ ᴀʀᴇ ɴᴏᴛ ᴘᴇʀᴍɪᴛᴛᴇᴅ."
                : "ꑬ &cᴛʜɪꜱ ᴜꜱᴇʀɴᴀᴍᴇ ɪꜱ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴛᴏ ᴀ ᴍᴏᴊᴀɴɢ ᴀᴄᴄᴏᴜɴᴛ. ᴘʟᴇᴀꜱᴇ ʟᴏɢ ɪɴ ᴡɪᴛʜ ʏᴏᴜʀ ᴏꜰꜰɪᴄɪᴀʟ ᴀᴄᴄᴏᴜɴᴛ ᴏʀ ᴄʜᴏᴏꜱᴇ ᴀ ᴅɪꜰꜰᴇʀᴇɴᴛ ɴᴀᴍᴇ.";

        String formatted = plugin.getMessage(msgKey, defaultMsg);
        Component kickReason = LegacyComponentSerializer.legacySection().deserialize(ChatColor.translateAlternateColorCodes('&', formatted));
        user.sendPacket(new WrapperLoginServerDisconnect(kickReason));
        user.closeConnection();
    }

    private static String connectionKey(User user) {
        InetSocketAddress addr = user.getAddress();
        if (addr != null && addr.getAddress() != null) {
            return addr.getAddress().getHostAddress() + ":" + addr.getPort();
        }
        return String.valueOf(user.hashCode());
    }
}
