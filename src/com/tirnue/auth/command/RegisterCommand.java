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

public class RegisterCommand implements CommandExecutor {
    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;

    public RegisterCommand(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager) {
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
            plugin.sendMessage(player, "register-usage", "ꑪ &bᴜꜱᴀɢᴇ: &f/{label} <ᴘᴀꜱꜱᴡᴏʀᴅ> [ᴄᴏɴꜰɪʀᴍᴘᴀꜱꜱᴡᴏʀᴅ]", "{label}", label);
            return true;
        }

        String password = args[0];
        String confirm = args.length >= 2 ? args[1] : null;

        int minLen = plugin.getConfig().getInt("security.min-password-length", 4);
        int maxLen = plugin.getConfig().getInt("security.max-password-length", 64);

        if (password.length() < minLen) {
            plugin.sendMessage(player, "password-too-short", "ꑬ &cᴘᴀꜱꜱᴡᴏʀᴅ ɪꜱ ᴛᴏᴏ ꜱʜᴏʀᴛ. ᴍɪɴɪᴍᴜᴍ ʟᴇɴɢᴛʜ ɪꜱ &f{min} &cᴄʜᴀʀᴀᴄᴛᴇʀꜱ.",
                    "{min}", String.valueOf(minLen));
            return true;
        }

        if (password.length() > maxLen) {
            plugin.sendMessage(player, "password-too-long", "ꑬ &cᴘᴀꜱꜱᴡᴏʀᴅ ᴇxᴄᴇᴇᴅꜱ ᴍᴀxɪᴍᴜᴍ ʟᴇɴɢᴛʜ ᴏꜰ &f{max} &cᴄʜᴀʀᴀᴄᴛᴇʀꜱ.",
                    "{max}", String.valueOf(maxLen));
            return true;
        }

        if (confirm != null && !password.equals(confirm)) {
            plugin.sendMessage(player, "passwords-dont-match", "ꑬ &cᴘᴀꜱꜱᴡᴏʀᴅꜱ ᴅɪᴅ ɴᴏᴛ ᴍᴀᴛᴄʜ, ᴄʜᴇᴄᴋ ᴛʜᴇᴍ ᴀɢᴀɪɴ!");
            return true;
        }

        String name = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "127.0.0.1";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (db.isRegistered(name)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.sendMessage(player, "already-registered", "ꑬ &cᴛʜɪꜱ ᴜꜱᴇʀɴᴀᴍᴇ ɪꜱ ᴀʟʀᴇᴀᴅʏ ʀᴇɢɪꜱᴛᴇʀᴇᴅ! ᴜꜱᴇ &b/login <ᴘᴀꜱꜱᴡᴏʀᴅ>&c.");
                });
                return;
            }

            int maxPerIp = plugin.getConfig().getInt("security.max-registrations-per-ip", 4);
            if (maxPerIp > 0 && db.countRegistrationsByIp(ip) >= maxPerIp) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.sendMessage(player, "max-accounts-exceeded", "ꑬ &cᴍᴀxɪᴍᴜᴍ ʀᴇɢɪꜱᴛᴇʀᴇᴅ ᴀᴄᴄᴏᴜɴᴛꜱ ᴘᴇʀ ɪᴘ ᴀᴅᴅʀᴇꜱꜱ ʀᴇᴀᴄʜᴇᴅ (&f{max}&c).",
                            "{max}", String.valueOf(maxPerIp));
                });
                return;
            }

            String hash = PasswordSecurity.hashPassword(password);
            UserAccount account = new UserAccount(
                    name,
                    player.getUniqueId(),
                    hash,
                    ip,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    UserAccount.AuthType.CRACKED,
                    false
            );

            db.saveUser(account);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    sessionManager.authenticate(player, plugin.getMessage("register-success", "ꑫ &aᴛʜᴀɴᴋ ʏᴏᴜ ꜰᴏʀ ʟᴏɢɢɪɴɢ ɪɴ!"));
                }
            });
        });

        return true;
    }
}
