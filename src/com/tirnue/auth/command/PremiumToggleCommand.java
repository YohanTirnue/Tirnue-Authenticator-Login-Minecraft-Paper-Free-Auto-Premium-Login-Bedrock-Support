package com.tirnue.auth.command;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.mojang.MojangService;
import com.tirnue.auth.security.SessionManager;
import com.tirnue.auth.storage.DatabaseManager;
import com.tirnue.auth.storage.UserAccount;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public class PremiumToggleCommand implements CommandExecutor {
    private final TirnueAuth plugin;
    private final DatabaseManager db;
    private final SessionManager sessionManager;
    private final MojangService mojangService;

    public PremiumToggleCommand(TirnueAuth plugin, DatabaseManager db, SessionManager sessionManager, MojangService mojangService) {
        this.plugin = plugin;
        this.db = db;
        this.sessionManager = sessionManager;
        this.mojangService = mojangService;
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

        String name = player.getName();
        if (name.startsWith(".")) {
            plugin.sendMessage(player, "bedrock-cannot-toggle", "ꑭ &eʙᴇᴅʀᴏᴄᴋ ᴀᴄᴄᴏᴜɴᴛꜱ ᴀʀᴇ ᴀᴜᴛᴏᴍᴀᴛɪᴄᴀʟʟʏ ᴠᴇʀɪꜰɪᴇᴅ ᴠɪᴀ xʙᴏx ʟɪᴠᴇ ᴀɴᴅ ꜰʟᴏᴏᴅɢᴀᴛᴇ!");
            return true;
        }

        boolean isSettingPremium = label.equalsIgnoreCase("premium") || label.equalsIgnoreCase("prem") || label.equalsIgnoreCase("tpremium");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<UserAccount> userOpt = db.getUser(name);
            if (!userOpt.isPresent()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.sendMessage(player, "not-registered", "ꑬ &cᴛʜɪꜱ ᴜꜱᴇʀɴᴀᴍᴇ ɪꜱ ɴᴏᴛ ʀᴇɢɪꜱᴛᴇʀᴇᴅ! ᴜꜱᴇ &b/register <ᴘᴀꜱꜱᴡᴏʀᴅ> <ᴄᴏɴꜰɪʀᴍᴘᴀꜱꜱᴡᴏʀᴅ>&c.");
                });
                return;
            }

            UserAccount user = userOpt.get();

            if (isSettingPremium) {
                if (user.getAuthType() == UserAccount.AuthType.PREMIUM) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.sendMessage(player, "already-premium", "ꑫ &aʏᴏᴜʀ ᴀᴄᴄᴏᴜɴᴛ ɪꜱ ᴀʟʀᴇᴀᴅʏ ᴠᴇʀɪꜰɪᴇᴅ ᴀꜱ ᴀɴ ᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴀᴄᴄᴏᴜɴᴛ.");
                    });
                    return;
                }

                // Check with Mojang
                Optional<UUID> mojangUuid = mojangService.queryMojang(name);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (mojangUuid.isPresent()) {
                        user.setAuthType(UserAccount.AuthType.PREMIUM);
                        user.setManualOverride(true);
                        user.setUuid(mojangUuid.get());
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.saveUser(user));

                        plugin.sendMessage(player, "premium-success", "ꑫ &aᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴀᴄᴄᴏᴜɴᴛ ᴠᴇʀɪꜰɪᴇᴅ! ᴀᴜᴛᴏ-ʟᴏɢɪɴ ɪꜱ ɴᴏᴡ ᴀᴄᴛɪᴠᴇ.");
                    } else {
                        plugin.sendMessage(player, "premium-not-found", "ꑬ &cɴᴏ ᴏꜰꜰɪᴄɪᴀʟ ᴍᴏᴊᴀɴɢ ᴘᴀɪᴅ ᴀᴄᴄᴏᴜɴᴛ ꜰᴏᴜɴᴅ ᴡɪᴛʜ ᴜꜱᴇʀɴᴀᴍᴇ: &f{player}&c.",
                                "{player}", name);
                    }
                });
            } else {
                // Switching to cracked
                user.setAuthType(UserAccount.AuthType.CRACKED);
                user.setManualOverride(true);
                db.saveUser(user);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.sendMessage(player, "switched-cracked", "ꑭ &eʏᴏᴜʀ ᴀᴄᴄᴏᴜɴᴛ ʜᴀꜱ ʙᴇᴇɴ ꜱᴡɪᴛᴄʜᴇᴅ ᴛᴏ ᴘᴀꜱꜱᴡᴏʀᴅ ᴍᴏᴅᴇ.");
                });
            }
        });

        return true;
    }
}
