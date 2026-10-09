package com.tirnue.auth.command;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LogoutCommand implements CommandExecutor {
    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;

    public LogoutCommand(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager) {
        this.plugin = plugin;
        this.db = db;
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.sendMessage(sender, "only-players", "ꑬ &cᴛʜɪꜱ ᴄᴏᴍᴍᴀɴᴅ ᴄᴀɴ ᴏɴʟʏ ʙᴇ ᴇxᴇᴄᴜᴛᴇᴅ ʙʏ ᴘʟᴀʏᴇʀꜱ ɪɴ-ɢᴀᴍᴇ.");
            return true;
        }

        Player player = (Player) sender;
        if (!sessionManager.isAuthenticated(player.getUniqueId())) {
            plugin.sendMessage(player, "not-logged-in", "ꑬ &cʏᴏᴜ ᴀʀᴇ ɴᴏᴛ ʟᴏɢɢᴇᴅ ɪɴ!");
            return true;
        }

        String name = player.getName();

        // Invalidate persistent session in DB asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            db.invalidateSession(name);
        });

        // Clean up authenticated status and disconnect so they log in via dialog
        sessionManager.cleanup(player);
        String logoutKick = plugin.getMessage("logout-kick", "ꑮ &dʏᴏᴜ ʜᴀᴠᴇ ʙᴇᴇɴ ʟᴏɢɢᴇᴅ ᴏᴜᴛ. ᴘʟᴇᴀꜱᴇ ʀᴇᴄᴏɴɴᴇᴄᴛ ᴛᴏ ʟᴏɢ ɪɴ ᴀɢᴀɪɴ.");
        player.kick(net.kyori.adventure.text.Component.text(plugin.stripColor(plugin.color(logoutKick))));
        return true;
    }
}
