package dev.crossbridge.spigot;

import dev.crossbridge.common.protocol.BridgeMessages;
import dev.crossbridge.common.protocol.BridgePacket;
import dev.crossbridge.common.protocol.PacketType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class BridgeAPI {
    private final JavaPlugin plugin;
    private final String serverId;
    private final String sharedSecret;
    private final AtomicLong ids = new AtomicLong();
    private final Map<String, BridgeHandler> handlers = new ConcurrentHashMap<>();
    private final Map<Long, BridgeHandler> responseHandlers = new ConcurrentHashMap<>();

    BridgeAPI(JavaPlugin plugin, String serverId, String sharedSecret) {
        this.plugin = plugin;
        this.serverId = serverId;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    }

    public String getServerId() { return serverId; }

    public void registerHandler(String action, BridgeHandler handler) {
        if (action == null || action.isBlank() || handler == null) throw new IllegalArgumentException("action/handler required");
        handlers.put(action.toLowerCase(), handler);
    }

    public void unregisterHandler(String action) {
        if (action != null) handlers.remove(action.toLowerCase());
    }

    public long send(String targetServer, String action, String payload) {
        return send(null, targetServer, action, payload, null);
    }

    public long send(Player carrier, String targetServer, String action, String payload) {
        return send(carrier, targetServer, action, payload, null);
    }

    public long send(String targetServer, String action, String payload, BridgeHandler onResponse) {
        return send(null, targetServer, action, payload, onResponse);
    }

    public long send(Player carrier, String targetServer, String action, String payload, BridgeHandler onResponse) {
        long id = ids.incrementAndGet();
        if (onResponse != null) responseHandlers.put(id, onResponse);
        BridgePacket packet = BridgeMessages.custom(id, serverId, targetServer, action, payload);
        if (!sendPacket(carrier, packet) && onResponse != null) responseHandlers.remove(id);
        return id;
    }

    public void sendToProxy(Player carrier, String action, String payload) {
        send(carrier, "proxy", action, payload);
    }

    public void broadcast(String action, String payload) {
        send("*", action, payload);
    }

    public void ping() {
        sendPacket(null, BridgeMessages.ping(ids.incrementAndGet(), serverId));
    }

    void handlePacket(BridgePacket packet) {
        if (packet.type() == PacketType.RESPONSE || packet.type() == PacketType.PONG) {
            BridgeHandler response = responseHandlers.remove(packet.requestId());
            if (response != null) response.handle(packet);
            return;
        }
        BridgeHandler handler = handlers.get(packet.action().toLowerCase());
        if (handler != null) {
            try { handler.handle(packet); }
            catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Bridge handler '" + packet.action() + "' failed", t);
            }
        }
    }

    boolean sendPacket(Player preferredCarrier, BridgePacket packet) {
        Player carrier = preferredCarrier;
        if (carrier == null || !carrier.isOnline()) {
            carrier = plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
        }
        if (carrier == null) {
            if (((CrossBridgeSpigot) plugin).isDebug()) {
                plugin.getLogger().warning("Cannot send CrossBridge packet: no player is connected on this backend.");
            }
            return false;
        }
        try {
            carrier.sendPluginMessage(plugin, CrossBridgeSpigot.CHANNEL, packet.encode(sharedSecret));
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Failed to send CrossBridge packet: " + ex.getMessage());
            return false;
        }
    }

    public boolean isSecured() {
        return !sharedSecret.isBlank();
    }

    public java.util.List<String> getKnownServerNames() {
        return ((CrossBridgeSpigot) plugin).getKnownServers();
    }

    public String getSharedSecretFingerprint() {
        if (sharedSecret.isBlank()) return "disabled";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sharedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(8);
            for (int i = 0; i < 4; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (java.security.GeneralSecurityException e) {
            return "unknown";
        }
    }
}
