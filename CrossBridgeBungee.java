package dev.crossbridge.bungee;

import dev.crossbridge.common.protocol.BridgeActions;
import dev.crossbridge.common.protocol.BridgeMessages;
import dev.crossbridge.common.protocol.BridgePacket;
import dev.crossbridge.common.protocol.PacketType;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CrossBridgeBungee extends Plugin implements Listener {
    public static final String CHANNEL = "crossbridge:main";

    private final AtomicLong ids = new AtomicLong();
    private final Map<String, Long> backendLastSeen = new ConcurrentHashMap<>();
    private final Map<String, BridgeProxyHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, ServerInfo> dynamicServers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pingState = new ConcurrentHashMap<>();

    private ManagedServerRegistry registry;
    private ProxySecurity security;
    private String sharedSecret;
    private boolean authRequired;
    private boolean debug;
    private boolean securityEnabled;
    private int maxMessagesPerSecond;
    private int invalidPacketThreshold;
    private long securityLockoutSeconds;
    private long heartbeatExpirySeconds;
    private long pingIntervalSeconds;
    private String fallbackServer;
    private ScheduledTask probeTask;

    @Override
    public void onEnable() {
        loadLocal();
        registry = new ManagedServerRegistry(getDataFolder());
        registry.load();
        registerManagedServers();

        getProxy().registerChannel(CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new BridgeProxyCommand(this));

        scheduleProbes();
        getLogger().info("XeonProxy enabled. Servers=" + allServers().size() + ", managed=" + dynamicServers.size());
        if (authRequired && sharedSecret.length() < 16) {
            getLogger().warning("Proxy authentication is enabled but shared-secret is weak (<16 chars). Set a long random secret on proxy and every backend.");
        }
        if (getProxy().getConfig() != null && !isIpForwardEnabled()) {
            getLogger().warning("Bungee ip_forward is disabled. UUID/IP forwarding security may not match your network design.");
        }
    }

    @Override
    public void onDisable() {
        if (probeTask != null) probeTask.cancel();
        getProxy().getPluginManager().unregisterListeners(this);
        getProxy().getPluginManager().unregisterCommands(this);
        getProxy().unregisterChannel(CHANNEL);
    }

    public CrossBridgeBungee getBridge() { return this; }

    public void reloadLocal() {
        loadLocal();
        if (registry == null) registry = new ManagedServerRegistry(getDataFolder());
        registry.load();
        registerManagedServers();
        scheduleProbes();
    }

    private void loadLocal() {
        Map<String, String> config = new HashMap<>();
        File file = new File(getDataFolder(), "config.yml");
        getDataFolder().mkdirs();
        if (!file.exists()) {
            try (InputStream in = getResourceAsStream("config.yml")) {
                if (in != null) java.nio.file.Files.copy(in, file.toPath());
            } catch (IOException ex) {
                getLogger().warning("Could not create config.yml: " + ex.getMessage());
            }
        }
        try {
            if (file.exists()) {
                for (String raw : java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.startsWith("#") || !line.contains(":")) continue;
                    String[] parts = line.split(":", 2);
                    config.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException ex) {
            getLogger().warning("Could not read config.yml: " + ex.getMessage());
        }

        securityEnabled = bool(config, "security-enabled", true);
        authRequired = bool(config, "auth-required", true);
        sharedSecret = unquote(config.getOrDefault("shared-secret", "CHANGE-ME-please-use-a-random-secret"));
        debug = bool(config, "debug", false);
        maxMessagesPerSecond = integer(config, "max-messages-per-second", 30, 1, 5000);
        invalidPacketThreshold = integer(config, "invalid-packet-threshold", 5, 1, 100);
        securityLockoutSeconds = integer(config, "security-lockout-seconds", 15, 1, 3600);
        heartbeatExpirySeconds = integer(config, "heartbeat-expiry-seconds", 20, 5, 3600);
        pingIntervalSeconds = integer(config, "ping-interval-seconds", 15, 5, 3600);
        fallbackServer = unquote(config.getOrDefault("fallback-server", "lobby"));
        security = new ProxySecurity(maxMessagesPerSecond, invalidPacketThreshold, securityLockoutSeconds);
    }

    private boolean bool(Map<String, String> map, String key, boolean fallback) {
        return Boolean.parseBoolean(map.getOrDefault(key, Boolean.toString(fallback)).replace("'", "").replace("\"", ""));
    }

    private int integer(Map<String, String> map, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(map.getOrDefault(key, Integer.toString(fallback)).trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String unquote(String value) {
        return value == null ? "" : value.trim().replaceAll("^\\\"|\\\"$", "").replaceAll("^'|'$", "");
    }

    private void registerManagedServers() {
        for (ManagedServerRegistry.Definition definition : registry.definitions()) {
            try {
                if (getProxy().getServers().containsKey(definition.name())) continue;
                ServerInfo info = registry.materialize(definition);
                dynamicServers.put(definition.name().toLowerCase(Locale.ROOT), info);
                try {
                    getProxy().getServers().put(definition.name(), info);
                } catch (UnsupportedOperationException | IllegalArgumentException ex) {
                    getLogger().warning("Proxy implementation does not expose a mutable server map; managed server '" + definition.name() + "' remains Xeon-only.");
                }
            } catch (RuntimeException ex) {
                getLogger().warning("Could not register managed server " + definition.name() + ": " + ex.getMessage());
            }
        }
    }

    ServerInfo createManagedServer(ManagedServerRegistry.Definition definition) {
        if (findServer(definition.name()) != null) throw new IllegalStateException("server already exists");
        if (!registry.create(definition)) throw new IllegalStateException("server already exists in registry");
        ServerInfo info;
        try {
            info = registry.materialize(definition);
            dynamicServers.put(definition.name().toLowerCase(Locale.ROOT), info);
            try {
                getProxy().getServers().put(definition.name(), info);
            } catch (UnsupportedOperationException | IllegalArgumentException ex) {
                getLogger().warning("Runtime Bungee server map is not mutable. The server will work through XeonProxy commands but may not appear in Bungee's native /server list.");
            }
            syncServerList();
            return info;
        } catch (RuntimeException ex) {
            registry.delete(definition.name());
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    void deleteManagedServer(String name) {
        ServerInfo info = findServer(name);
        if (info == null) throw new IllegalStateException("Server non trovato: " + name);
        if (!isManaged(name)) throw new IllegalStateException("Il server è configurato nel proxy e non appartiene al registry di XeonProxy.");
        dynamicServers.remove(name.toLowerCase(Locale.ROOT));
        try { getProxy().getServers().remove(name); } catch (UnsupportedOperationException ignored) { }
        if (!registry.delete(name)) throw new IllegalStateException("Server presente runtime ma non nel registry.");
        syncServerList();
    }

    ServerInfo getDynamicServer(String name) { return dynamicServers.get(name.toLowerCase(Locale.ROOT)); }

    public ServerInfo findServer(String name) {
        if (name == null) return null;
        ServerInfo dynamic = dynamicServers.get(name.toLowerCase(Locale.ROOT));
        return dynamic != null ? dynamic : getProxy().getServerInfo(name);
    }

    Collection<ServerInfo> allServers() {
        Map<String, ServerInfo> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(getProxy().getServers());
        merged.putAll(dynamicServers);
        return List.copyOf(merged.values());
    }

    Collection<String> serverNames() {
        return allServers().stream().map(ServerInfo::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    boolean isManaged(String name) { return registry.get(name) != null; }

    String linkStatus(String serverName) {
        Long seen = backendLastSeen.get(serverName);
        if (seen == null) return "UNKNOWN";
        long age = System.currentTimeMillis() - seen;
        if (age <= heartbeatExpirySeconds * 1000L) return "LINKED";
        if (age <= heartbeatExpirySeconds * 3000L) return "STALE";
        return "OFFLINE";
    }

    int linkedCount() {
        int count = 0;
        for (String server : serverNames()) if ("LINKED".equals(linkStatus(server))) count++;
        return count;
    }

    public long sendToServer(String target, String action, String payload) {
        long id = ids.incrementAndGet();
        deliver(target, BridgeMessages.custom(id, "proxy", target, action, payload));
        return id;
    }

    public void broadcast(String action, String payload) {
        for (ServerInfo server : allServers()) {
            deliver(server.getName(), BridgeMessages.custom(ids.incrementAndGet(), "proxy", server.getName(), action, payload));
        }
    }

    public boolean connect(ProxiedPlayer player, String targetServer) {
        ServerInfo target = findServer(targetServer);
        if (target == null) return false;
        player.connect(target);
        return true;
    }

    public void message(ProxiedPlayer player, String message) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    public void kick(ProxiedPlayer player, String reason) {
        player.disconnect(ChatColor.translateAlternateColorCodes('&', reason));
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getTag())) return;
        if (!(event.getSender() instanceof Server senderConnection)) return;

        ServerInfo sender = senderConnection.getInfo();
        String senderName = sender.getName();
        if (securityEnabled && !security.allowed(senderName)) {
            if (debug) getLogger().warning("Rate/lockout rejected packet from " + senderName);
            return;
        }

        BridgePacket packet;
        try {
            packet = authRequired
                    ? BridgePacket.decode(event.getData(), sharedSecret)
                    : BridgePacket.decode(event.getData(), "");
        } catch (IOException | IllegalArgumentException ex) {
            boolean locked = securityEnabled && security.invalid(senderName);
            getLogger().warning("Rejected CrossBridge packet from " + senderName + ": " + ex.getMessage()
                    + (locked ? " (source temporarily locked)" : ""));
            return;
        }

        if (!packet.source().equalsIgnoreCase(senderName)) {
            boolean locked = securityEnabled && security.invalid(senderName);
            getLogger().warning("Rejected spoofed CrossBridge source '" + packet.source() + "' from " + senderName
                    + (locked ? " (source temporarily locked)" : ""));
            return;
        }

        if (securityEnabled) security.valid(senderName);
        backendLastSeen.put(senderName, System.currentTimeMillis());
        if (debug) getLogger().info("RX " + packet.type() + " action=" + packet.action()
                + " from=" + packet.source() + " on=" + senderName);

        if (packet.type() == PacketType.HELLO) {
            deliver(senderName, BridgeMessages.response(packet.requestId(), "proxy", senderName, "hello", "ok"));
            deliver(senderName, BridgeMessages.custom(ids.incrementAndGet(), "proxy", senderName, BridgeActions.SERVER_LIST, String.join(",", serverNames())));
            return;
        }
        if (packet.type() == PacketType.PING) {
            deliver(senderName, BridgeMessages.pong(packet.requestId(), "proxy", senderName));
            return;
        }
        if (packet.type() != PacketType.CUSTOM) return;

        if (packet.target().equalsIgnoreCase("proxy")) {
            handleProxyAction(sender, packet);
            return;
        }
        routeFromBackend(sender, packet);
    }

    private void handleProxyAction(ServerInfo sender, BridgePacket packet) {
        if (BridgeActions.PLAYER_CONNECT.equalsIgnoreCase(packet.action())) {
            String[] parts = packet.payload().split("\\|", 2);
            if (parts.length != 2) return;
            UUID uuid;
            try { uuid = UUID.fromString(parts[0]); }
            catch (IllegalArgumentException ex) { return; }
            ProxiedPlayer player = getProxy().getPlayer(uuid);
            if (player == null || player.getServer() == null) return;
            if (!player.getServer().getInfo().getName().equalsIgnoreCase(sender.getName())) return;
            ServerInfo target = findServer(parts[1]);
            if (target == null || target.getName().equalsIgnoreCase(sender.getName())) return;
            player.connect(target);
            return;
        }

        if (BridgeActions.PLAYER_MESSAGE.equalsIgnoreCase(packet.action())) {
            String[] parts = packet.payload().split("\\|", 2);
            if (parts.length != 2) return;
            try {
                ProxiedPlayer player = getProxy().getPlayer(UUID.fromString(parts[0]));
                if (player != null && player.getServer() != null
                        && player.getServer().getInfo().getName().equalsIgnoreCase(sender.getName())) {
                    message(player, parts[1]);
                }
            } catch (IllegalArgumentException ignored) { }
            return;
        }

        BridgeProxyHandler handler = handlers.get(packet.action().toLowerCase(Locale.ROOT));
        if (handler != null) {
            try { handler.handle(packet); }
            catch (Throwable t) { getLogger().log(java.util.logging.Level.SEVERE, "Proxy bridge handler failed: " + packet.action(), t); }
        }
    }

    private void routeFromBackend(ServerInfo sender, BridgePacket packet) {
        String target = packet.target();
        if (target.equals("*")) {
            for (ServerInfo server : allServers()) {
                if (!server.getName().equalsIgnoreCase(sender.getName())) deliver(server.getName(), packet);
            }
            return;
        }
        if (target.equalsIgnoreCase("proxy")) return;
        deliver(target, packet);
    }

    private void deliver(String serverName, BridgePacket packet) {
        ServerInfo target = findServer(serverName);
        if (target == null) {
            getLogger().warning("Unknown backend '" + serverName + "'.");
            return;
        }
        try {
            boolean immediate = target.sendData(CHANNEL, packet.encode(sharedSecret), true);
            if (debug && !immediate) getLogger().fine("Queued packet for backend " + serverName + ".");
        } catch (IOException ex) {
            getLogger().warning("Could not encode packet for " + serverName + ": " + ex.getMessage());
        }
    }

    private void syncServerList() {
        String payload = String.join(",", serverNames());
        for (String server : serverNames()) {
            deliver(server, BridgeMessages.custom(ids.incrementAndGet(), "proxy", server, BridgeActions.SERVER_LIST, payload));
        }
    }

    private void scheduleProbes() {
        if (probeTask != null) probeTask.cancel();
        probeTask = getProxy().getScheduler().schedule(this, this::probeServers, 5, Math.max(5, pingIntervalSeconds), TimeUnit.SECONDS);
    }

    private void probeServers() {
        for (ServerInfo server : allServers()) {
            server.ping((result, error) -> pingState.put(server.getName(), error == null && result != null));
        }
    }

    boolean isPingUp(String serverName) { return pingState.getOrDefault(serverName, false); }

    String getFallbackServer() { return fallbackServer; }
    boolean isAuthEnabled() { return authRequired && !sharedSecret.isBlank(); }
    int getMaxMessagesPerSecond() { return maxMessagesPerSecond; }
    int getTrackedSecuritySources() { return security == null ? 0 : security.trackedSources(); }
    boolean isIpForwardEnabled() {
        try { return getProxy().getConfig() != null && getProxy().getConfig().isIpForward(); }
        catch (Throwable ignored) { return false; }
    }
    boolean isOnlineModeEnabled() {
        try { return getProxy().getConfig() != null && getProxy().getConfig().isOnlineMode(); }
        catch (Throwable ignored) { return false; }
    }
}
