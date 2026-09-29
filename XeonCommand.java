package dev.crossbridge.spigot;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class XeonCommand implements TabExecutor {
    private final CrossBridgeSpigot plugin;

    XeonCommand(CrossBridgeSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "server" -> server(sender, args);
                case "info" -> info(sender);
                case "ping" -> {
                    require(sender, "crossbridge.command.ping");
                    plugin.getBridge().ping();
                    msg(sender, ChatColor.YELLOW + "Ping inviato al proxy.");
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException ex) {
            msg(sender, ChatColor.RED + ex.getMessage());
        }
        return true;
    }

    private void server(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.server");
        if (args.length != 2) throw new IllegalArgumentException("Uso: /xeon server <nome>");
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            throw new IllegalArgumentException("Questo comando di collegamento deve essere eseguito da un giocatore.");
        }
        String target = args[1];
        if (target.equalsIgnoreCase(plugin.getBridge().getServerId())) {
            msg(sender, ChatColor.YELLOW + "Sei già su questo server.");
            return;
        }
        plugin.getBridge().sendToProxy(player, "player.connect", player.getUniqueId() + "|" + target);
        msg(sender, ChatColor.GREEN + "Richiesta di collegamento a " + target + " inviata al proxy.");
    }

    private void info(CommandSender sender) {
        require(sender, "crossbridge.command.info");
        msg(sender, ChatColor.GREEN + "CrossBridge " + plugin.getDescription().getVersion()
                + ChatColor.GRAY + " | server=" + plugin.getBridge().getServerId()
                + " | secure=" + plugin.getBridge().isSecured()
                + " | fp=" + plugin.getBridge().getSharedSecretFingerprint());
    }

    private void help(CommandSender sender) {
        msg(sender, ChatColor.GOLD + "CrossBridge network commands:");
        msg(sender, ChatColor.GRAY + "/xeon server <server>  /  /xeon info  /  /xeon ping");
        msg(sender, ChatColor.GRAY + "I comandi Bukkit base sono stati sostituiti direttamente da CrossBridge.");
    }

    private void require(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) throw new IllegalArgumentException("Non hai il permesso " + permission + ".");
    }

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return partial(args[0], List.of("server", "info", "ping"));
        if (args.length == 2 && args[0].equalsIgnoreCase("server")) return partial(args[1], plugin.getBridge().getKnownServerNames());
        return List.of();
    }

    private List<String> partial(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
