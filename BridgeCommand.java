package dev.crossbridge.spigot;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

final class BridgeCommand implements CommandExecutor {
    private final CrossBridgeSpigot plugin;

    BridgeCommand(CrossBridgeSpigot plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("crossbridge.use")) return true;
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(ChatColor.GREEN + "CrossBridge " + plugin.getDescription().getVersion()
                    + " | backend=" + plugin.getServerId()
                    + " | secure=" + plugin.getBridge().isSecured()
                    + " | fp=" + plugin.getBridge().getSharedSecretFingerprint());
            sender.sendMessage(ChatColor.GRAY + "Online: " + plugin.getServer().getOnlinePlayers().size()
                    + "/" + plugin.getServer().getMaxPlayers());
            return true;
        }
        if (args[0].equalsIgnoreCase("ping")) {
            plugin.getBridge().ping();
            sender.sendMessage(ChatColor.YELLOW + "Ping inviato al proxy.");
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Uso: /bridge <info|ping>");
        return true;
    }
}
