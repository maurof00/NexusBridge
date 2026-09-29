package dev.crossbridge.velocity;

import dev.crossbridge.common.protocol.BridgeActions;
import dev.crossbridge.common.protocol.BridgeMessages;
import dev.crossbridge.common.protocol.BridgePacket;
import dev.crossbridge.common.protocol.PacketType;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Native Velocity adapter. The same universal JAR also contains Spigot and Bungee adapters. */
public final class CrossBridgeVelocity {
    public static final String CHANNEL = "crossbridge:main";
    private static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from(CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<String, Long> backendLastSeen = new ConcurrentHashMap<>();
    private final Map<String, ServerInfo> dynamicServers = new ConcurrentHashMap<>();

    private ManagedServerRegistryVelocity registry;
    private VelocitySecurity security;
    private String sharedSecret = "";
    private boolean authRequired = true;
    private boolean securityEnabled = true;
    private boolean debug;
    private long heartbeatExpirySeconds = 20;

    @Inject
    public CrossBridgeVelocity(ProxyServer proxy, Logger logger) {
        this(proxy, logger, Path.of("plugins", "CrossBridge"));
    }

    public CrossBridgeVelocity(ProxyServer proxy, Logger logger, Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            ensureDataDirectory();
            loadConfig();
            registry = new ManagedServerRegistryVelocity(dataDirectory.toFile());
            registry.load();
            for (ManagedServerRegistryVelocity.Definition definition : registry.definitions()) {
                ServerInfo info = registry.materialize(definition);
                dynamicServers.put(definition.name().toLowerCase(Locale.ROOT), info);
                if (proxy.getServer(definition.name()).isEmpty()) proxy.registerServer(info);
            }
        } catch (RuntimeException ex) {
            logger.error("CrossBridge Velocity failed to initialise registry", ex);
            return;
        }

        proxy.getChannelRegistrar().register(IDENTIFIER);
        CommandMeta meta = proxy.getCommandManager().metaBuilder("xeonProxy")
                .plugin(this)
                .aliases("xproxy", "bridge")
                .build();
        proxy.getCommandManager().register(meta, new VelocityProxyCommand(this));

        logger.info("XeonProxy / CrossBridge enabled on Velocity. servers={}, managed={}, secure={}",
                proxy.getAllServers().size(), dynamicServers.size(), isAuthEnabled());
    }

    private void ensureDataDirectory() {
        try { Files.createDirectories(dataDirectory); }
        catch (IOException ex) { throw new IllegalStateException("Could not create data directory", ex); }
    }

    private void loadConfig() {
        Path config = dataDirectory.resolve("config.yml");
        if (!Files.exists(config)) {
            try (var in = CrossBridgeVelocity.class.getResourceAsStream("/config.yml")) {
                if (in != null) Files.copy(in, config);
            } catch (IOException ex) {
                throw new IllegalStateException("Could not create config.yml", ex);
            }
        }
        Map<String, String> values = new HashMap<>();
        try {
            for (String raw : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.startsWith("#") || !line.contains(":")) continue;
                String[] parts = line.split(":", 2);
                values.put(parts[0].trim(), unquote(parts[1].trim()));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read config.yml", ex);
        }
        securityEnabled = bool(values, "security-enabled", true);
        authRequired = bool(values, "auth-required", true);
        sharedSecret = values.getOrDefault("shared-secret", "").trim();
        debug = bool(values, "debug", false);
        heartbeatExpirySeconds = Math.max(5, integer(values, "heartbeat-expiry-seconds", 20));
        int rate = integer(values, "max-messages-per-second", 30);
        int invalid = integer(values, "invalid-packet-threshold", 5);
        long lockout = Math.max(5, integer(values, "security-lockout-seconds", 15));
        security = new VelocitySecurity(rate, invalid, lockout);
    }

    private boolean bool(Map<String, String> values, String key, boolean fallback) {
        return Boolean.parseBoolean(values.getOrDefault(key, Boolean.toString(fallback)));
    }

    private int integer(Map<String, String> values, String key, int fallback) {
        try { return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!IDENTIFIER.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection backend)) return;

        String senderName = backend.getServerInfo().getName();
        if (securityEnabled && (security == null || !security.allowed(senderName))) {
            if (debug) logger.warn("Rejected/rate-limited packet from {}", senderName);
            return;
        }

        BridgePacket packet;
        try {
            packet = authRequired
                    ? BridgePacket.decode(event.getData(), sharedSecret)
                    : BridgePacket.decode(event.getData(), "");
        } catch (IOException | IllegalArgumentException ex) {
            boolean locked = securityEnabled && security.invalid(senderName);
            logger.warn("Rejected CrossBridge packet from {}{}: {}", senderName,
                    locked ? " (temporarily locked)" : "", ex.getMessage());
            return;
        }
        if (!packet.source().equalsIgnoreCase(senderName)) {
            boolean locked = securityEnabled && security.invalid(senderName);
            logger.warn("Rejected spoofed source {} from {}{}", packet.source(), senderName,
                    locked ? " (temporarily locked)" : "");
            return;
        }
        if (securityEnabled) security.valid(senderName);
        backendLastSeen.put(senderName.toLowerCase(Locale.ROOT), System.currentTimeMillis());

        if (packet.type() == PacketType.HELLO) {
            sendToServer(senderName, BridgeMessages.response(packet.requestId(), "proxy", senderName, "hello", "ok"));
            sendToServer(senderName, BridgeMessages.custom(System.nanoTime(), "proxy", senderName,
                    BridgeActions.SERVER_LIST, String.join(",", serverNames())));
            return;
        }
        if (packet.type() == PacketType.PING) {
            sendToServer(senderName, BridgeMessages.pong(packet.requestId(), "proxy", senderName));
            return;
        }
        if (packet.type() != PacketType.CUSTOM) return;

        if (packet.target().equalsIgnoreCase("proxy")) {
            handleProxyAction(senderName, packet);
            return;
        }
        route(packet);
    }

    private void handleProxyAction(String sourceBackend, BridgePacket packet) {
        if (BridgeActions.PLAYER_CONNECT.equalsIgnoreCase(packet.action())) {
            String[] parts = packet.payload().split("\\|", 2);
            if (parts.length != 2) return;
            UUID uuid;
            try { uuid = UUID.fromString(parts[0]); }
            catch (IllegalArgumentException ex) { return; }
            Optional<Player> optional = proxy.getPlayer(uuid);
            if (optional.isEmpty()) return;
            Player player = optional.get();
            Optional<ServerConnection> current = player.getCurrentServer();
            if (current.isEmpty() || !current.get().getServerInfo().getName().equalsIgnoreCase(sourceBackend)) return;
            RegisteredServer target = findServer(parts[1]);
            if (target == null) return;
            player.createConnectionRequest(target).fireAndForget();
            return;
        }
        if (BridgeActions.PLAYER_MESSAGE.equalsIgnoreCase(packet.action())) {
            String[] parts = packet.payload().split("\\|", 2);
            if (parts.length != 2) return;
            try {
                Optional<Player> player = proxy.getPlayer(UUID.fromString(parts[0]));
                if (player.isPresent()) player.get().sendPlainMessage(parts[1]);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void route(BridgePacket packet) {
        if ("*".equals(packet.target())) {
            for (RegisteredServer server : proxy.getAllServers()) {
                if (!server.getServerInfo().getName().equalsIgnoreCase(packet.source())) sendToServer(server.getServerInfo().getName(), packet);
            }
            return;
        }
        sendToServer(packet.target(), packet);
    }

    public boolean sendToServer(String target, BridgePacket packet) {
        RegisteredServer server = findServer(target);
        if (server == null) return false;
        try {
            return server.sendPluginMessage(IDENTIFIER, packet.encode(sharedSecret));
        } catch (IOException | RuntimeException ex) {
            if (debug) logger.warn("Could not send CrossBridge packet to {}: {}", target, ex.getMessage());
            return false;
        }
    }

    public boolean connect(Player player, String target) {
        RegisteredServer server = findServer(target);
        if (server == null) return false;
        player.createConnectionRequest(server).fireAndForget();
        return true;
    }

    public RegisteredServer findServer(String name) {
        if (name == null) return null;
        RegisteredServer dynamic = proxy.getServer(name).orElse(null);
        return dynamic;
    }

    public Collection<RegisteredServer> allServers() {
        List<RegisteredServer> servers = new ArrayList<>(proxy.getAllServers());
        servers.sort(Comparator.comparing(s -> s.getServerInfo().getName(), String.CASE_INSENSITIVE_ORDER));
        return servers;
    }

    public List<String> serverNames() {
        return allServers().stream().map(s -> s.getServerInfo().getName()).toList();
    }

    public boolean isManaged(String name) { return registry != null && registry.get(name) != null; }

    public String linkStatus(String name) {
        Long seen = backendLastSeen.get(name.toLowerCase(Locale.ROOT));
        if (seen == null) return "UNKNOWN";
        long age = System.currentTimeMillis() - seen;
        if (age <= heartbeatExpirySeconds * 1000L) return "LINKED";
        if (age <= heartbeatExpirySeconds * 3000L) return "STALE";
        return "OFFLINE";
    }

    public int linkedCount() {
        int count = 0;
        for (String name : serverNames()) if ("LINKED".equals(linkStatus(name))) count++;
        return count;
    }

    public boolean createManagedServer(String name, String host, int port, boolean restricted) {
        if (proxy.getServer(name).isPresent()) return false;
        ManagedServerRegistryVelocity.Definition definition =
                new ManagedServerRegistryVelocity.Definition(name, host, port, restricted);
        if (!registry.create(definition)) return false;
        ServerInfo info = registry.materialize(definition);
        dynamicServers.put(name.toLowerCase(Locale.ROOT), info);
        proxy.registerServer(info);
        syncServerList();
        return true;
    }

    public boolean deleteManagedServer(String name) {
        RegisteredServer registered = findServer(name);
        if (registered == null || !isManaged(name)) return false;
        ServerInfo info = registered.getServerInfo();
        proxy.unregisterServer(info);
        dynamicServers.remove(name.toLowerCase(Locale.ROOT));
        return registry.delete(name);
    }

    public void syncServerList() {
        String payload = String.join(",", serverNames());
        for (String name : serverNames()) {
            sendToServer(name, BridgeMessages.custom(System.nanoTime(), "proxy", name, BridgeActions.SERVER_LIST, payload));
        }
    }

    public String backendSummary(String name) {
        RegisteredServer server = findServer(name);
        if (server == null) return "not-found";
        return server.getServerInfo().getName() + " -> " + server.getServerInfo().getAddress()
                + " | players=" + server.getPlayersConnected().size()
                + " | bridge=" + linkStatus(name)
                + (isManaged(name) ? " | managed=true" : " | managed=false");
    }

    public boolean isAuthEnabled() { return authRequired && !sharedSecret.isBlank(); }
    public int getTrackedSecuritySources() { return security == null ? 0 : security.trackedSources(); }
    public boolean isSecurityEnabled() { return securityEnabled; }
    public boolean isDebug() { return debug; }
    public String getSharedSecretFingerprint() {
        if (sharedSecret.isBlank()) return "disabled";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(sharedSecret.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(8);
            for (int i = 0; i < 4; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception ex) { return "unknown"; }
    }

    public ProxyServer getProxy() { return proxy; }
}
