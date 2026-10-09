package com.tirnue.auth.command;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.security.PasswordSecurity;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

public class ChangePasswordCommand implements CommandExecutor {
    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;

    public ChangePasswordCommand(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager) {
        this.plugin = plugin;
        this.db = db;
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.sendMessage(sender, "only-players", "&cᴛʜɪꜱ ᴄᴏᴍᴍᴀɴᴅ ᴄᴀɴ ᴏɴʟʏ ʙᴇ ᴇxᴇᴄᴜᴛᴇᴅ ʙʏ ᴘʟᴀʏᴇʀꜱ ɪɴ-ɢᴀᴍᴇ.");
            return true;
        }

        Player player = (Player) sender;
        if (!sessionManager.isAuthenticated(player.getUniqueId())) {
            plugin.sendMessage(player, "not-logged-in", "ꑬ &cʏᴏᴜ ᴍᴜꜱᴛ ʙᴇ ʟᴏɢɢᴇᴅ ɪɴ ᴛᴏ ᴜꜱᴇ ᴛʜɪꜱ ᴄᴏᴍᴍᴀɴᴅ.");
            return true;
        }

        Optional<UserAccount> checkOpt = db.getUser(player.getName());
        if (checkOpt.isPresent() && checkOpt.get().getAuthType() == UserAccount.AuthType.PREMIUM) {
            plugin.sendMessage(player, "premium-changepassword-disabled", "ꑬ &cᴛʜɪꜱ ᴀᴄᴄᴏᴜɴᴛ ɪꜱ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴀꜱ ᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴘʀᴇᴍɪᴜᴍ. ᴘᴀꜱꜱᴡᴏʀᴅ ᴄʜᴀɴɢᴇ ɪꜱ ᴅɪꜱᴀʙʟᴇᴅ.");
            return true;
        }

        if (args.length < 1) {
            plugin.sendMessage(player, "changepassword-usage", "ꑪ &bᴜꜱᴀɢᴇ: &f/{label} <ᴏʟᴅᴘᴀꜱꜱᴡᴏʀᴅ> <ɴᴇᴡᴘᴀꜱꜱᴡᴏʀᴅ>", "{label}", label);
            return true;
        }

        int minLen = plugin.getConfig().getInt("security.min-password-length", 4);
        int maxLen = plugin.getConfig().getInt("security.max-password-length", 64);

        boolean singleArgMode = (args.length == 1);
        String oldPass = singleArgMode ? null : args[0];
        String newPass = singleArgMode ? args[0] : args[1];

        if (!singleArgMode && oldPass.length() > maxLen) {
            plugin.sendMessage(player, "password-too-long", "ꑬ &cᴘᴀꜱꜱᴡᴏʀᴅ ᴇxᴄᴇᴇᴅꜱ ᴍᴀxɪᴍᴜᴍ ʟᴇɴɢᴛʜ ᴏꜰ &f{max} &cᴄʜᴀʀᴀᴄᴛᴇʀꜱ.",
                    "{max}", String.valueOf(maxLen));
            return true;
        }

        if (newPass.length() < minLen) {
            plugin.sendMessage(player, "password-too-short", "ꑬ &cᴘᴀꜱꜱᴡᴏʀᴅ ɪꜱ ᴛᴏᴏ ꜱʜᴏʀᴛ. ᴍɪɴɪᴍᴜᴍ ʟᴇɴɢᴛʜ ɪꜱ &f{min} &cᴄʜᴀʀᴀᴄᴛᴇʀꜱ.",
                    "{min}", String.valueOf(minLen));
            return true;
        }

        if (newPass.length() > maxLen) {
            plugin.sendMessage(player, "password-too-long", "ꑬ &cᴘᴀꜱꜱᴡᴏʀᴅ ᴇxᴄᴇᴇᴅꜱ ᴍᴀxɪᴍᴜᴍ ʟᴇɴɢᴛʜ ᴏꜰ &f{max} &cᴄʜᴀʀᴀᴄᴛᴇʀꜱ.",
                    "{max}", String.valueOf(maxLen));
            return true;
        }

        SessionManager.AuthThrottleResult throttle = sessionManager.checkAndLockAuth(player.getUniqueId());
        if (throttle == SessionManager.AuthThrottleResult.CONCURRENT_IN_PROGRESS) {
            return true;
        }
        if (throttle == SessionManager.AuthThrottleResult.RATE_LIMITED) {
            plugin.sendMessage(player, "auth-throttled", "ꑬ &cᴘʟᴇᴀꜱᴇ ᴡᴀɪᴛ ᴀ ᴍᴏᴍᴇɴᴛ ʙᴇꜰᴏʀᴇ ᴛʀʏɪɴɢ ᴀɢᴀɪɴ.");
            return true;
        }

        String name = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<UserAccount> userOpt = db.getUser(name);
                if (!userOpt.isPresent()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.sendMessage(player, "not-registered", "ꑬ &cᴛʜɪꜱ ᴜꜱᴇʀɴᴀᴍᴇ ɪꜱ ɴᴏᴛ ʀᴇɢɪꜱᴛᴇʀᴇᴅ! ᴜꜱᴇ &b/register <ᴘᴀꜱꜱᴡᴏʀᴅ> <ᴄᴏɴꜰɪʀᴍᴘᴀꜱꜱᴡᴏʀᴅ>&c.");
                    });
                    return;
                }

                UserAccount user = userOpt.get();
                boolean isPlaceholder = "$PREMIUM$".equals(user.getPasswordHash());

                if (singleArgMode && !isPlaceholder) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.sendMessage(player, "changepassword-usage", "ꑪ &bᴜꜱᴀɢᴇ: &f/{label} <ᴏʟᴅᴘᴀꜱꜱᴡᴏʀᴅ> <ɴᴇᴡᴘᴀꜱꜱᴡᴏʀᴅ>", "{label}", label);
                    });
                    return;
                }

                if (!isPlaceholder && !PasswordSecurity.checkPassword(oldPass, user.getPasswordHash())) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.sendMessage(player, "old-password-wrong", "ꑬ &cʏᴏᴜʀ ᴄᴜʀʀᴇɴᴛ ᴘᴀꜱꜱᴡᴏʀᴅ ᴡᴀꜱ ɪɴᴄᴏʀʀᴇᴄᴛ.");
                    });
                    return;
                }

                String newHash = PasswordSecurity.hashPassword(newPass);
                user.setPasswordHash(newHash);
                db.saveUser(user);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.sendMessage(player, "password-changed", "ꑫ &aᴘᴀꜱꜱᴡᴏʀᴅ ᴄʜᴀɴɢᴇᴅ ꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ!");
                });
            } finally {
                sessionManager.releaseAuthLock(player.getUniqueId());
            }
        });

        return true;
    }
}
