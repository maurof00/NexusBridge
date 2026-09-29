package dev.crossbridge.bungee;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.CommandSender;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.*;
import java.util.stream.Collectors;

final class BridgeProxyCommand extends Command implements TabExecutor {
    private final CrossBridgeBungee plugin;

    BridgeProxyCommand(CrossBridgeBungee plugin) {
        super("xeonProxy", "xeonproxy.admin", "xproxy", "bridge");
        this.plugin = plugin;
        setPermissionMessage(ChatColor.RED + "Non hai il permesso di usare XeonProxy.");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!hasPermission(sender)) return;
        if (args.length == 0) {
            help(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "server" -> server(sender, args);
            case "servers", "serverlist", "network" -> listServersStatus(sender);
            case "player" -> player(sender, args);
            case "broadcast" -> broadcast(sender, args);
            case "protect", "security" -> protect(sender, args);
            case "status" -> status(sender);
            case "reload" -> {
                plugin.reloadLocal();
                sender.sendMessage(ChatColor.GREEN + "XeonProxy ricaricato.");
            }
            default -> quickServer(sender, args);
        }
    }

    private void server(CommandSender sender, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("status")) {
            listServersStatus(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> createServer(sender, args);
            case "delete", "remove" -> deleteServer(sender, args);
            case "info" -> infoServer(sender, args);
            case "ping" -> pingServer(sender, args.length >= 3 ? args[2] : null);
            case "connect" -> connectPlayer(sender, args);
                        default -> sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy server <list|create|delete|info|ping|connect>");
        }
    }

    private void createServer(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy server create <nome> <host:porta> [motd]");
            return;
        }
        String name = args[2].toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            sender.sendMessage(ChatColor.RED + "Nome server non valido. Usa 1-32 caratteri: a-z, 0-9, _ o -.");
            return;
        }
        if (plugin.getProxy().getServers().containsKey(name) || plugin.getDynamicServer(name) != null) {
            sender.sendMessage(ChatColor.RED + "Server già esistente: " + name);
            return;
        }
        HostPort endpoint;
        try { endpoint = parseEndpoint(args[3]); }
        catch (IllegalArgumentException ex) { sender.sendMessage(ChatColor.RED + ex.getMessage()); return; }
        String motd = args.length >= 5 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : "XeonProxy server";
        ManagedServerRegistry.Definition definition = new ManagedServerRegistry.Definition(name, endpoint.host(), endpoint.port(), motd, false);
        try {
            ServerInfo info = plugin.createManagedServer(definition);
            sender.sendMessage(ChatColor.GREEN + "Server creato: " + info.getName() + " -> " + endpoint.host() + ":" + endpoint.port());
            sender.sendMessage(ChatColor.GRAY + "Persistito nel registry di XeonProxy. È immediatamente utilizzabile dai comandi del plugin.");
        } catch (IllegalStateException ex) {
            sender.sendMessage(ChatColor.RED + "Impossibile creare il server: " + ex.getMessage());
        }
    }

    private void deleteServer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy server delete <nome> [--force]");
            return;
        }
        String name = args[2];
        boolean force = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("--force"));
        ServerInfo info = plugin.findServer(name);
        if (info == null) {
            sender.sendMessage(ChatColor.RED + "Server non trovato: " + name);
            return;
        }
        if (!force && !info.getPlayers().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Ci sono " + info.getPlayers().size() + " giocatori collegati. Usa --force per eliminarlo comunque.");
            return;
        }
        if (force) {
            String fallbackName = plugin.getFallbackServer();
            ServerInfo fallback = fallbackName == null ? null : plugin.findServer(fallbackName);
            for (ProxiedPlayer player : new ArrayList<>(info.getPlayers())) {
                if (fallback != null && !fallback.getName().equalsIgnoreCase(name)) player.connect(fallback);
                else player.disconnect(ChatColor.RED + "Il server è stato rimosso dal proxy.");
            }
        }
        try {
            plugin.deleteManagedServer(name);
            sender.sendMessage(ChatColor.GREEN + "Server rimosso: " + name);
        } catch (IllegalStateException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        }
    }

    private void infoServer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy server info <nome>");
            return;
        }
        ServerInfo info = plugin.findServer(args[2]);
        if (info == null) {
            sender.sendMessage(ChatColor.RED + "Server non trovato: " + args[2]);
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "--- Server " + info.getName() + " ---");
        sender.sendMessage(ChatColor.GRAY + "Address: " + info.getSocketAddress());
        sender.sendMessage(ChatColor.GRAY + "Players: " + info.getPlayers().size());
        sender.sendMessage(ChatColor.GRAY + "Motd: " + info.getMotd());
        sender.sendMessage(ChatColor.GRAY + "Restricted: " + info.isRestricted());
        sender.sendMessage(ChatColor.GRAY + "Managed: " + plugin.isManaged(info.getName()));
        sender.sendMessage(ChatColor.GRAY + "Bridge: " + plugin.linkStatus(info.getName()));
        pingServer(sender, info.getName());
    }

    private void pingServer(CommandSender sender, String name) {
        if (name == null) {
            sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy server ping <nome>");
            return;
        }
        ServerInfo info = plugin.findServer(name);
        if (info == null) {
            sender.sendMessage(ChatColor.RED + "Server non trovato: " + name);
            return;
        }
        info.ping((ServerPing result, Throwable error) -> {
            if (error != null || result == null || result.getPlayers() == null) {
                sender.sendMessage(ChatColor.RED + "Ping fallito per " + info.getName() + ".");
                return;
            }
            int online = result.getPlayers().getOnline();
            int max = result.getPlayers().getMax();
            String version = result.getVersion() == null ? "?" : result.getVersion().getName();
            sender.sendMessage(ChatColor.GREEN + "Ping OK " + info.getName()
                    + ChatColor.GRAY + " | players=" + online + "/" + max + " | mc=" + version);
        });
    }

    private void connectPlayer(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy server connect <giocatore> <server>");
            return;
        }
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(args[2]);
        ServerInfo target = plugin.findServer(args[3]);
        if (player == null) { sender.sendMessage(ChatColor.RED + "Giocatore non trovato."); return; }
        if (target == null) { sender.sendMessage(ChatColor.RED + "Server non trovato."); return; }
        player.connect(target, (ok, error) -> sender.sendMessage(ok
                ? ChatColor.GREEN + "Collegamento di " + player.getName() + " a " + target.getName() + " completato."
                : ChatColor.RED + "Collegamento fallito: " + (error == null ? "sconosciuto" : error.getMessage())));
    }

    private void player(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy player <send|message|kick|info> ...");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(args[2]);
        if (player == null) { sender.sendMessage(ChatColor.RED + "Giocatore non trovato: " + args[2]); return; }
        switch (action) {
            case "send" -> {
                if (args.length != 4) { sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy player send <giocatore> <server>"); return; }
                ServerInfo target = plugin.findServer(args[3]);
                if (target == null) { sender.sendMessage(ChatColor.RED + "Server non trovato."); return; }
                player.connect(target);
                sender.sendMessage(ChatColor.GREEN + "Richiesta inviata.");
            }
            case "message", "msg" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy player message <giocatore> <messaggio>"); return; }
                plugin.message(player, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                sender.sendMessage(ChatColor.GREEN + "Messaggio inviato.");
            }
            case "kick" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy player kick <giocatore> <motivo>"); return; }
                plugin.kick(player, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                sender.sendMessage(ChatColor.GREEN + "Giocatore espulso.");
            }
            case "info" -> {
                sender.sendMessage(ChatColor.GOLD + player.getName() + ChatColor.GRAY + " | uuid=" + player.getUniqueId());
                sender.sendMessage(ChatColor.GRAY + "server=" + (player.getServer() == null ? "?" : player.getServer().getInfo().getName())
                        + " | ping=" + player.getPing());
            }
            default -> sender.sendMessage(ChatColor.RED + "Azione player sconosciuta.");
        }
    }

    private void broadcast(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Uso: /xeonProxy broadcast <messaggio>");
            return;
        }
        plugin.getProxy().broadcast(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                ChatColor.translateAlternateColorCodes('&', String.join(" ", Arrays.copyOfRange(args, 1, args.length)))));
        sender.sendMessage(ChatColor.GREEN + "Broadcast inviato.");
    }

    private void protect(CommandSender sender, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GOLD + "--- XeonProxy Protection ---");
            sender.sendMessage(ChatColor.GRAY + "HMAC: " + (plugin.isAuthEnabled() ? "enabled" : "disabled"));
            sender.sendMessage(ChatColor.GRAY + "Rate limit: " + plugin.getMaxMessagesPerSecond() + "/s per backend");
            sender.sendMessage(ChatColor.GRAY + "Tracked backend sources: " + plugin.getTrackedSecuritySources());
            sender.sendMessage(ChatColor.GRAY + "ip-forward: " + plugin.isIpForwardEnabled());
            sender.sendMessage(ChatColor.GRAY + "online-mode: " + plugin.isOnlineModeEnabled());
            return;
        }
        sender.sendMessage(ChatColor.RED + "La protezione viene configurata in plugins/XeonProxy/config.yml e ricaricata con /xeonProxy reload.");
    }

    private void status(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "XeonProxy " + plugin.getDescription().getVersion()
                + ChatColor.GRAY + " | proxy=" + plugin.getProxy().getName() + " " + plugin.getProxy().getVersion()
                + " | players=" + plugin.getProxy().getOnlineCount());
        sender.sendMessage(ChatColor.GRAY + "servers=" + plugin.allServers().size() + " | linked=" + plugin.linkedCount()
                + " | secure=" + plugin.isAuthEnabled());
    }

    private void quickServer(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            help(sender);
            return;
        }
        ServerInfo target = plugin.findServer(args[0]);
        if (target == null) {
            help(sender);
            return;
        }
        player.connect(target);
    }

    private void listServers(CommandSender sender) {
        listServersStatus(sender);
    }

    private void listServersStatus(CommandSender sender) {
        List<ServerInfo> servers = plugin.allServers().stream()
                .sorted(Comparator.comparing(ServerInfo::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        sender.sendMessage(ChatColor.GOLD + "--- XeonProxy Network ---");
        sender.sendMessage(ChatColor.GRAY + "Server                          Status       Bridge       Players");
        if (servers.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Nessun server registrato nel proxy.");
            return;
        }
        for (ServerInfo info : servers) {
            boolean online = plugin.isPingUp(info.getName());
            String status = online ? ChatColor.GREEN + "ONLINE " : ChatColor.RED + "OFFLINE";
            String bridge = switch (plugin.linkStatus(info.getName())) {
                case "LINKED" -> ChatColor.GREEN + "LINKED";
                case "STALE" -> ChatColor.YELLOW + "STALE";
                case "OFFLINE" -> ChatColor.RED + "OFFLINE";
                default -> ChatColor.GRAY + "UNKNOWN";
            };
            String managed = plugin.isManaged(info.getName()) ? ChatColor.AQUA + " [managed]" : "";
            sender.sendMessage(ChatColor.WHITE + String.format("%-30s", info.getName())
                    + " " + String.format("%-12s", status)
                    + " " + String.format("%-12s", bridge)
                    + " " + ChatColor.GRAY + info.getPlayers().size() + managed);
        }
        long online = servers.stream().filter(s -> plugin.isPingUp(s.getName())).count();
        long linked = servers.stream().filter(s -> "LINKED".equals(plugin.linkStatus(s.getName()))).count();
        int players = servers.stream().mapToInt(s -> s.getPlayers().size()).sum();
        sender.sendMessage(ChatColor.GOLD + "Totale: " + ChatColor.WHITE + online + "/" + servers.size()
                + ChatColor.GRAY + " online | " + ChatColor.GREEN + linked + " linked"
                + ChatColor.GRAY + " | players=" + players);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "XeonProxy commands:");
        sender.sendMessage(ChatColor.GRAY + "/xeonProxy servers  |  /xeonProxy server <list|status|create|delete|info|ping|connect>");
        sender.sendMessage(ChatColor.GRAY + "/xeonProxy player <send|message|kick|info> ...");
        sender.sendMessage(ChatColor.GRAY + "/xeonProxy broadcast <messaggio>  |  /xeonProxy protect status");
        sender.sendMessage(ChatColor.GRAY + "/xeonProxy status  |  /xeonProxy reload");
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(args[0], List.of("servers", "server", "player", "broadcast", "protect", "security", "status", "reload"), true);
        if (args[0].equalsIgnoreCase("server")) {
            if (args.length == 2) return filter(args[1], List.of("list", "status", "create", "delete", "info", "ping", "connect"), true);
            if (args.length == 3 && Set.of("delete", "info", "ping").contains(args[1].toLowerCase(Locale.ROOT))) return filter(args[2], plugin.serverNames(), false);
            if (args.length == 4 && args[1].equalsIgnoreCase("connect")) {
                return filter(args[3], plugin.serverNames(), false);
            }
        }
        if (args[0].equalsIgnoreCase("player")) {
            if (args.length == 2) return filter(args[1], List.of("send", "message", "kick", "info"), true);
            if (args.length == 3) return filter(args[2], ProxyServer.getInstance().getPlayers().stream().map(ProxiedPlayer::getName).toList(), false);
            if (args.length == 4 && args[1].equalsIgnoreCase("send")) return filter(args[3], plugin.serverNames(), false);
        }
        if (args[0].equalsIgnoreCase("protect") && args.length == 2) return filter(args[1], List.of("status"), true);
        return List.of();
    }

    private List<String> filter(String prefix, Collection<String> values, boolean sort) {
        String p = prefix.toLowerCase(Locale.ROOT);
        var out = values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p));
        if (sort) out = out.sorted(String.CASE_INSENSITIVE_ORDER);
        return out.toList();
    }

    private HostPort parseEndpoint(String raw) {
        int idx = raw.lastIndexOf(':');
        if (idx <= 0 || idx == raw.length() - 1) throw new IllegalArgumentException("Endpoint non valido. Usa host:porta, es. 127.0.0.1:25565");
        String host = raw.substring(0, idx).trim();
        int port;
        try { port = Integer.parseInt(raw.substring(idx + 1)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Porta non valida: " + raw); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("La porta deve essere tra 1 e 65535.");
        return new HostPort(host, port);
    }

    private record HostPort(String host, int port) {}
}
