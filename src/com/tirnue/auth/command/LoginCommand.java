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

public class LoginCommand implements CommandExecutor {
    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;

    public LoginCommand(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager) {
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
        if (sessionManager.isAuthenticated(player.getUniqueId())) {
            plugin.sendMessage(player, "already-logged-in", "ꑫ &aʏᴏᴜ ᴀʀᴇ ᴀʟʀᴇᴀᴅʏ ʟᴏɢɢᴇᴅ ɪɴ!");
            return true;
        }

        if (args.length < 1) {
            plugin.sendMessage(player, "login-usage", "ꑪ &bᴜꜱᴀɢᴇ: &f/{label} <ᴘᴀꜱꜱᴡᴏʀᴅ>", "{label}", label);
            return true;
        }

        String password = args[0];
        int maxLen = plugin.getConfig().getInt("security.max-password-length", 64);
        if (password.length() > maxLen) {
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
                boolean valid = PasswordSecurity.checkPassword(password, user.getPasswordHash());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;

                    if (valid) {
                        sessionManager.authenticate(player, plugin.getMessage("login-success", "ꑫ &aᴛʜᴀɴᴋ ʏᴏᴜ ꜰᴏʀ ʟᴏɢɢɪɴɢ ɪɴ!"));
                    } else {
                        sessionManager.recordFailedAttempt(player);
                    }
                });
            } finally {
                sessionManager.releaseAuthLock(player.getUniqueId());
            }
        });

        return true;
    }
}
