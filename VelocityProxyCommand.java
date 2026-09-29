package dev.crossbridge.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class VelocityProxyCommand implements SimpleCommand {
    private final CrossBridgeVelocity plugin;

    VelocityProxyCommand(CrossBridgeVelocity plugin) { this.plugin = plugin; }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("xeonproxy.admin");
    }

    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission("xeonproxy.admin")) {
            invocation.source().sendPlainMessage("§cNon hai il permesso.");
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) { help(invocation); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "server" -> server(invocation, args);
            case "servers", "serverlist", "network" -> listServersStatus(invocation);
            case "player" -> player(invocation, args);
            case "broadcast" -> broadcast(invocation, args);
            case "protect", "security" -> protect(invocation, args);
            case "status" -> status(invocation);
            default -> help(invocation);
        }
    }

    private void server(Invocation inv, String[] args) {
        if (args.length == 1 || "list".equalsIgnoreCase(args[1]) || "status".equalsIgnoreCase(args[1])) {
            listServersStatus(inv);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> create(inv, args);
            case "delete", "remove" -> delete(inv, args);
            case "info" -> info(inv, args);
            case "ping" -> ping(inv, args);
            case "connect" -> connect(inv, args);
            default -> inv.source().sendPlainMessage("§cUso: /xeonProxy servers | /xeonProxy server <list|status|create|delete|info|ping|connect>");
        }
    }

    private void listServersStatus(Invocation inv) {
        List<RegisteredServer> servers = plugin.allServers().stream()
                .sorted(Comparator.comparing(s -> s.getServerInfo().getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        inv.source().sendPlainMessage("§6--- XeonProxy Network ---");
        if (servers.isEmpty()) {
            inv.source().sendPlainMessage("§eNessun server registrato nel proxy.");
            return;
        }
        inv.source().sendPlainMessage("§7Server                         Status       Bridge       Players");

        List<CompletableFuture<ServerStatus>> checks = new ArrayList<>(servers.size());
        for (RegisteredServer server : servers) {
            long started = System.nanoTime();
            CompletableFuture<ServerStatus> check;
            try {
                check = server.ping()
                        .thenApply(ping -> new ServerStatus(server, true, (System.nanoTime() - started) / 1_000_000L))
                        .exceptionally(ex -> new ServerStatus(server, false, -1));
            } catch (RuntimeException ex) {
                check = CompletableFuture.completedFuture(new ServerStatus(server, false, -1));
            }
            checks.add(check);
        }
        CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenRun(() -> {
            long online = 0;
            long linked = 0;
            int players = 0;
            for (CompletableFuture<ServerStatus> future : checks) {
                ServerStatus row = future.join();
                String name = row.server().getServerInfo().getName();
                String status = row.online() ? "§aONLINE " : "§cOFFLINE";
                String bridge = switch (plugin.linkStatus(name)) {
                    case "LINKED" -> "§aLINKED";
                    case "STALE" -> "§eSTALE";
                    case "OFFLINE" -> "§cOFFLINE";
                    default -> "§7UNKNOWN";
                };
                int count = row.server().getPlayersConnected().size();
                String ping = row.online() ? " §8(" + row.pingMs() + "ms)" : "";
                String managed = plugin.isManaged(name) ? " §b[managed]" : "";
                inv.source().sendPlainMessage("§f" + String.format("%-30s", name)
                        + " " + String.format("%-12s", status)
                        + " " + String.format("%-12s", bridge)
                        + " §7" + count + ping + managed);
                if (row.online()) online++;
                if ("LINKED".equals(plugin.linkStatus(name))) linked++;
                players += count;
            }
            inv.source().sendPlainMessage("§6Totale: §f" + online + "/" + servers.size()
                    + " §7online | §a" + linked + " linked §7| players=" + players);
        });
    }

    private record ServerStatus(RegisteredServer server, boolean online, long pingMs) {}

    private void create(Invocation inv, String[] args) {
        if (args.length < 4) {
            inv.source().sendPlainMessage("§cUso: /xeonProxy server create <nome> <host:porta> [restricted]");
            return;
        }
        String name = args[2].toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            inv.source().sendPlainMessage("§cNome non valido.");
            return;
        }
        HostPort endpoint;
        try { endpoint = parse(args[3]); }
        catch (IllegalArgumentException ex) { inv.source().sendPlainMessage("§c" + ex.getMessage()); return; }
        boolean restricted = args.length >= 5 && Boolean.parseBoolean(args[4]);
        if (!plugin.createManagedServer(name, endpoint.host(), endpoint.port(), restricted)) {
            inv.source().sendPlainMessage("§cServer già esistente o creazione fallita: " + name);
            return;
        }
        inv.source().sendPlainMessage("§aServer creato: " + name + " -> " + endpoint.host() + ":" + endpoint.port());
    }

    private void delete(Invocation inv, String[] args) {
        if (args.length < 3) {
            inv.source().sendPlainMessage("§cUso: /xeonProxy server delete <nome> [--force]");
            return;
        }
        if (plugin.getProxy().getServer(args[2]).isPresent() && !plugin.isManaged(args[2])) {
            inv.source().sendPlainMessage("§cIl server è definito in velocity.toml e non può essere eliminato da XeonProxy.");
            return;
        }
        if (!plugin.deleteManagedServer(args[2])) {
            inv.source().sendPlainMessage("§cServer managed non trovato: " + args[2]);
            return;
        }
        inv.source().sendPlainMessage("§aServer eliminato: " + args[2]);
    }

    private void info(Invocation inv, String[] args) {
        if (args.length < 3) { inv.source().sendPlainMessage("§cUso: /xeonProxy server info <nome>"); return; }
        RegisteredServer server = plugin.findServer(args[2]);
        if (server == null) { inv.source().sendPlainMessage("§cServer non trovato."); return; }
        server.ping().thenAccept(ping ->
            inv.source().sendPlainMessage("§6" + server.getServerInfo().getName() + " §7| address=" + server.getServerInfo().getAddress()
                    + " | players=" + server.getPlayersConnected().size() + " | ping=OK | bridge=" + plugin.linkStatus(args[2]))
        ).exceptionally(ex -> { inv.source().sendPlainMessage("§cPing fallito: " + ex.getMessage()); return null; });
    }

    private void ping(Invocation inv, String[] args) {
        if (args.length < 3) { inv.source().sendPlainMessage("§cUso: /xeonProxy server ping <nome>"); return; }
        RegisteredServer server = plugin.findServer(args[2]);
        if (server == null) { inv.source().sendPlainMessage("§cServer non trovato."); return; }
        long started = System.nanoTime();
        server.ping().thenAccept(ping -> inv.source().sendPlainMessage("§aPing OK §f" + server.getServerInfo().getName()
                + " §7| " + ((System.nanoTime() - started) / 1_000_000L) + "ms"))
                .exceptionally(ex -> { inv.source().sendPlainMessage("§cPing fallito: " + ex.getMessage()); return null; });
    }

    private void connect(Invocation inv, String[] args) {
        if (args.length != 4) {
            inv.source().sendPlainMessage("§cUso: /xeonProxy server connect <player> <server>");
            return;
        }
        var targetPlayer = plugin.getProxy().getPlayer(args[2]);
        if (targetPlayer.isEmpty()) { inv.source().sendPlainMessage("§cGiocatore non trovato."); return; }
        RegisteredServer target = plugin.findServer(args[3]);
        if (target == null) { inv.source().sendPlainMessage("§cServer non trovato."); return; }
        targetPlayer.get().createConnectionRequest(target).fireAndForget();
        inv.source().sendPlainMessage("§aConnessione richiesta per " + targetPlayer.get().getUsername() + ".");
    }

    private void player(Invocation inv, String[] args) {
        if (args.length < 3) { inv.source().sendPlainMessage("§cUso: /xeonProxy player <send|message|kick|info> ..."); return; }
        var optional = plugin.getProxy().getPlayer(args[2]);
        if (optional.isEmpty()) { inv.source().sendPlainMessage("§cGiocatore non trovato."); return; }
        Player player = optional.get();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "send" -> {
                if (args.length != 4) { inv.source().sendPlainMessage("§cUso: /xeonProxy player send <player> <server>"); return; }
                RegisteredServer target = plugin.findServer(args[3]);
                if (target == null) { inv.source().sendPlainMessage("§cServer non trovato."); return; }
                player.createConnectionRequest(target).fireAndForget();
                inv.source().sendPlainMessage("§aConnessione richiesta.");
            }
            case "message", "msg" -> {
                if (args.length < 4) { inv.source().sendPlainMessage("§cUso: /xeonProxy player message <player> <messaggio>"); return; }
                player.sendPlainMessage(String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
            }
            case "kick" -> {
                if (args.length < 4) { inv.source().sendPlainMessage("§cUso: /xeonProxy player kick <player> <motivo>"); return; }
                player.disconnect(net.kyori.adventure.text.Component.text(String.join(" ", Arrays.copyOfRange(args, 3, args.length))));
            }
            case "info" -> inv.source().sendPlainMessage("§6" + player.getUsername() + " §7| uuid=" + player.getUniqueId()
                    + " | ping=" + player.getPing() + " | server=" + player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("?"));
            default -> inv.source().sendPlainMessage("§cAzione sconosciuta.");
        }
    }

    private void broadcast(Invocation inv, String[] args) {
        if (args.length < 2) { inv.source().sendPlainMessage("§cUso: /xeonProxy broadcast <messaggio>"); return; }
        plugin.getProxy().getAllPlayers().forEach(player -> player.sendPlainMessage(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
        inv.source().sendPlainMessage("§aBroadcast inviato ai giocatori del proxy.");
    }

    private void protect(Invocation inv, String[] args) {
        inv.source().sendPlainMessage("§6--- XeonProxy Protection ---");
        inv.source().sendPlainMessage("§7Security: " + plugin.isSecurityEnabled() + " | HMAC: " + plugin.isAuthEnabled());
        inv.source().sendPlainMessage("§7Tracked backend sources: " + plugin.getTrackedSecuritySources());
        inv.source().sendPlainMessage("§7Secret fingerprint: " + plugin.getSharedSecretFingerprint());
    }

    private void status(Invocation inv) {
        var version = plugin.getProxy().getVersion();
        inv.source().sendPlainMessage("§aXeonProxy §f1.2.2 §7| Velocity " + version.getVersion()
                + " | players=" + plugin.getProxy().getPlayerCount());
        inv.source().sendPlainMessage("§7servers=" + plugin.allServers().size() + " | linked=" + plugin.linkedCount()
                + " | secure=" + plugin.isAuthEnabled());
    }

    private void help(Invocation inv) {
        inv.source().sendPlainMessage("§6XeonProxy:");
        inv.source().sendPlainMessage("§7/xeonProxy servers  |  /xeonProxy server <list|status|create|delete|info|ping|connect>");
        inv.source().sendPlainMessage("§7/xeonProxy player <send|message|kick|info> ...");
        inv.source().sendPlainMessage("§7/xeonProxy broadcast <messaggio> | protect | status");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) return List.of("servers", "server", "player", "broadcast", "protect", "status");
        if (args.length == 1) return prefix(args[0], List.of("server", "player", "broadcast", "protect", "status"));
        if (args[0].equalsIgnoreCase("server")) {
            if (args.length == 2) return prefix(args[1], List.of("list", "status", "create", "delete", "info", "ping", "connect"));
            if (args.length == 3 && Set.of("delete", "info", "ping").contains(args[1].toLowerCase(Locale.ROOT))) return prefix(args[2], plugin.serverNames());
            if (args.length == 4 && args[1].equalsIgnoreCase("connect")) return prefix(args[3], plugin.serverNames());
        }
        if (args[0].equalsIgnoreCase("player") && args.length == 3) {
            return prefix(args[2], plugin.getProxy().getAllPlayers().stream().map(Player::getUsername).toList());
        }
        return List.of();
    }

    private List<String> prefix(String prefix, Collection<String> values) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private HostPort parse(String raw) {
        int idx = raw.lastIndexOf(':');
        if (idx <= 0 || idx == raw.length() - 1) throw new IllegalArgumentException("Usa host:porta.");
        String host = raw.substring(0, idx);
        int port;
        try { port = Integer.parseInt(raw.substring(idx + 1)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Porta non valida."); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Porta fuori range.");
        return new HostPort(host, port);
    }

    private record HostPort(String host, int port) {}
}
