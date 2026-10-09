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

        Optional<UserAccount> accOpt = db.getUser(player.getName());
        if (accOpt.isPresent() && accOpt.get().getAuthType() == UserAccount.AuthType.PREMIUM) {
            plugin.sendMessage(player, "already-premium", "ꑫ &aʏᴏᴜʀ ᴀᴄᴄᴏᴜɴᴛ ɪꜱ ᴀʟʀᴇᴀᴅʏ ᴠᴇʀɪꜰɪᴇᴅ ᴀꜱ ᴀɴ ᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴀᴄᴄᴏᴜɴᴛ.");
            return true;
        }

        plugin.sendMessage(player, "login-disabled", "ꑬ &cɪɴ-ɢᴀᴍᴇ /ʀᴇɢɪꜱᴛᴇʀ ɪꜱ ᴅɪꜱᴀʙʟᴇᴅ. ᴀᴜᴛʜᴇɴᴛɪᴄᴀᴛɪᴏɴ ɪꜱ ᴄᴏᴍᴘʟᴇᴛᴇᴅ ᴠɪᴀ ᴛʜᴇ ʟᴏɢɪɴ ᴅɪᴀʟᴏɢ.");
        return true;
    }
}
