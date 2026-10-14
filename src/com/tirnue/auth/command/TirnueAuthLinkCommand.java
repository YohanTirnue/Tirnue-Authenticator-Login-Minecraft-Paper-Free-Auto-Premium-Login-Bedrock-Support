package com.tirnue.auth.command;

import com.tirnue.auth.TirnueAuth;
import com.tirnue.auth.link.AccountLinkManager;
import com.tirnue.auth.link.LinkedAccount;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class TirnueAuthLinkCommand implements CommandExecutor, TabCompleter {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final TirnueAuth plugin;
    private final AccountLinkManager linkManager;

    public TirnueAuthLinkCommand(TirnueAuth plugin, AccountLinkManager linkManager) {
        this.plugin = plugin;
        this.linkManager = linkManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("tirnue.auth.admin")) {
            sender.sendMessage(color("ꑬ &cʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪꜱꜱɪᴏɴ ᴛᴏ ᴇxᴇᴄᴜᴛᴇ ᴛʜɪꜱ ᴄᴏᴍᴍᴀɴᴅ."));
            return true;
        }

        String cmdName = command.getName().toLowerCase();

        // 1. Unlink command direct trigger
        if (cmdName.equals("tirnueauthunlink") || cmdName.equals("taunlink") || cmdName.equals("tunlink")) {
            if (args.length < 1) {
                sender.sendMessage(color("ꑬ &cᴜꜱᴀɢᴇ: &e/" + label + " <PlayerName>"));
                return true;
            }
            handleUnlink(sender, args[0]);
            return true;
        }

        // 2. Link command
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        // Subcommands: list, info, unlink, reload
        String first = args[0].toLowerCase();
        if (first.equals("list")) {
            handleList(sender);
            return true;
        }
        if (first.equals("info")) {
            if (args.length < 2) {
                sender.sendMessage(color("ꑬ &cᴜꜱᴀɢᴇ: &e/" + label + " info <PlayerName>"));
                return true;
            }
            handleInfo(sender, args[1]);
            return true;
        }
        if (first.equals("unlink")) {
            if (args.length < 2) {
                sender.sendMessage(color("ꑬ &cᴜꜱᴀɢᴇ: &e/" + label + " unlink <PlayerName>"));
                return true;
            }
            handleUnlink(sender, args[1]);
            return true;
        }
        if (first.equals("reload")) {
            linkManager.loadLinks();
            linkManager.hookFloodgate();
            sender.sendMessage(color("ꑫ &aʟɪɴᴋ ᴍᴀɴᴀɢᴇʀ ᴀɴᴅ ꜰʟᴏᴏᴅɢᴀᴛᴇ ʜᴏᴏᴋꜱ ʀᴇʟᴏᴀᴅᴇᴅ ꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ!"));
            return true;
        }

        // Standard link execution: /tirnueauthlink <OriginalName> <CopyName>
        if (args.length < 2) {
            sender.sendMessage(color("ꑬ &cᴜꜱᴀɢᴇ: &e/" + label + " <OriginalName> <CopyName>"));
            sender.sendMessage(color("&7• &fOriginalName&7: Account whose UUID and inventory will be the MASTER"));
            sender.sendMessage(color("&7• &fCopyName&7: Account that will COPY and sync to the original UUID"));
            sender.sendMessage(color("&7• &cNote&7: One must be Java and one must be Bedrock (with '.' prefix)!"));
            return true;
        }

        String original = args[0];
        String copy = args[1];

        sender.sendMessage(color("&7Linking &e" + original + " &7and &e" + copy + "&7..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AccountLinkManager.LinkResult result = linkManager.linkAccounts(original, copy);
            if (result.isSuccess()) {
                sender.sendMessage(color("ꑫ &a" + result.getMessage()));
            } else {
                sender.sendMessage(color("ꑬ &c" + result.getMessage()));
            }
        });

        return true;
    }

    private void handleUnlink(CommandSender sender, String name) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = linkManager.unlinkAccount(name);
            if (success) {
                sender.sendMessage(color("ꑫ &aꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ ᴜɴʟɪɴᴋᴇᴅ ᴀᴄᴄᴏᴜɴᴛ: &e" + name));
            } else {
                sender.sendMessage(color("ꑬ &cɴᴏ ᴀᴄᴛɪᴠᴇ ʟɪɴᴋ ꜰᴏᴜɴᴅ ꜰᴏʀ ᴘʟᴀʏᴇʀ: &e" + name));
            }
        });
    }

    private void handleInfo(CommandSender sender, String name) {
        Optional<LinkedAccount> linkOpt = linkManager.getLinkByName(name);
        if (!linkOpt.isPresent()) {
            sender.sendMessage(color("ꑬ &cɴᴏ ʟɪɴᴋ ꜰᴏᴜɴᴅ ꜰᴏʀ: &e" + name));
            return;
        }
        LinkedAccount link = linkOpt.get();
        sender.sendMessage(color("&8&m----------------------------------------"));
        sender.sendMessage(color("&#55cdfc&lʟɪɴᴋᴇᴅ ᴀᴄᴄᴏᴜɴᴛ ɪɴꜰᴏ"));
        sender.sendMessage(color("&7• &fBedrock Account: &e" + link.getBedrockName() + " &8(" + link.getBedrockUuid() + ")"));
        sender.sendMessage(color("&7• &fJava Account: &a" + link.getJavaName() + " &8(" + link.getJavaUuid() + ")"));
        sender.sendMessage(color("&7• &fMaster / Original: &6" + link.getMasterName() + " &e(" + link.getMasterType().name() + ")"));
        sender.sendMessage(color("&7• &fActive Synced UUID: &d" + link.getMasterUuid()));
        sender.sendMessage(color("&7• &fLinked At: &7" + DATE_FORMAT.format(new Date(link.getLinkedAt()))));
        sender.sendMessage(color("&8&m----------------------------------------"));
    }

    private void handleList(CommandSender sender) {
        List<LinkedAccount> links = linkManager.getAllLinks();
        if (links.isEmpty()) {
            sender.sendMessage(color("&eNo linked accounts found in TirnueAuth database."));
            return;
        }
        sender.sendMessage(color("&8&m----------------------------------------"));
        sender.sendMessage(color("&#55cdfc&lᴛɪʀɴᴜᴇᴀᴜᴛʜ ʟɪɴᴋᴇᴅ ᴀᴄᴄᴏᴜɴᴛꜱ &7(" + links.size() + ")"));
        for (LinkedAccount link : links) {
            String masterTag = link.isJavaMaster() ? "&a[Java Master]" : "&e[Bedrock Master]";
            sender.sendMessage(color("&7• &f" + link.getJavaName() + " &7↔ &f" + link.getBedrockName() + " " + masterTag + " &8-> &7" + link.getMasterUuid()));
        }
        sender.sendMessage(color("&8&m----------------------------------------"));
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(color("&8&m----------------------------------------"));
        sender.sendMessage(color("&#55cdfc&lᴛɪʀɴᴜᴇᴀᴜᴛʜ ᴀᴄᴄᴏᴜɴᴛ ʟɪɴᴋɪɴɢ"));
        sender.sendMessage(color("&e/" + label + " <Original> <Copy> &7- Link accounts (Copy gets Original's UUID)"));
        sender.sendMessage(color("&e/" + label + " unlink <Name> &7- Unlink a Java or Bedrock account"));
        sender.sendMessage(color("&e/" + label + " info <Name> &7- View linking details for a player"));
        sender.sendMessage(color("&e/" + label + " list &7- List all currently linked accounts"));
        sender.sendMessage(color("&e/" + label + " reload &7- Reload database links and Floodgate hooks"));
        sender.sendMessage(color("&8&m----------------------------------------"));
    }

    private String color(String msg) {
        return plugin.color(msg);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("tirnue.auth.admin")) return Collections.emptyList();

        if (args.length == 1) {
            List<String> list = new ArrayList<>(Arrays.asList("list", "info", "unlink", "reload"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                list.add(p.getName());
            }
            return list.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("info") || sub.equals("unlink")) {
                List<String> linkedNames = new ArrayList<>();
                for (LinkedAccount link : linkManager.getAllLinks()) {
                    linkedNames.add(link.getJavaName());
                    linkedNames.add(link.getBedrockName());
                }
                return linkedNames.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }

            // In /talink <Orig> <Copy>, suggest online players for second arg
            List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            return players.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
