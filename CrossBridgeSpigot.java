package dev.crossbridge.spigot;

import dev.crossbridge.common.protocol.BridgeActions;
import dev.crossbridge.common.protocol.BridgeMessages;
import dev.crossbridge.common.protocol.BridgePacket;
import dev.crossbridge.common.protocol.PacketType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CrossBridgeSpigot extends JavaPlugin implements org.bukkit.plugin.messaging.PluginMessageListener, Listener {
    public static final String CHANNEL = "crossbridge:main";

    private BridgeAPI api;
    private String serverId;
    private String sharedSecret;
    private boolean debug;
    private long heartbeatTicks;
    private XeonCommand xeonCommand;
    private NativeBukkitCommandExecutor nativeCommands;
    private BukkitCommandOverride commandOverrides;
    private final List<String> knownServers = new CopyOnWriteArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverId = getConfig().getString("server-id", getServer().getName()).trim();
        sharedSecret = getConfig().getString("shared-secret", "").trim();
        debug = getConfig().getBoolean("debug", false);
        long heartbeatSeconds = Math.max(2L, getConfig().getLong("heartbeat-seconds", 5L));
        heartbeatTicks = heartbeatSeconds * 20L;

        api = new BridgeAPI(this, serverId, sharedSecret);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        nativeCommands = new NativeBukkitCommandExecutor(this);
        installNativeCommandOverrides();

        xeonCommand = new XeonCommand(this);
        if (getCommand("xeon") != null) {
            getCommand("xeon").setExecutor(xeonCommand);
            getCommand("xeon").setTabCompleter(xeonCommand);
        }
        if (getCommand("bridge") != null) {
            getCommand("bridge").setExecutor(new BridgeCommand(this));
        }

        api.registerHandler("broadcast", packet -> Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', packet.payload())));
        api.registerHandler(BridgeActions.PLAYER_MESSAGE, packet -> handlePlayerMessage(packet.payload()));
        api.registerHandler(BridgeActions.SERVER_LIST, packet -> updateServerList(packet.payload()));
        knownServers.addAll(getConfig().getStringList("known-servers"));

        getLogger().info("Xeon/CrossBridge Spigot enabled for backend '" + serverId + "' (secure=" + api.isSecured() + ").");
        if (sharedSecret.isBlank()) {
            getLogger().warning("CrossBridge shared-secret is empty. Proxy authentication is disabled for this backend.");
        }
        Bukkit.getScheduler().runTaskTimer(this, this::sendHeartbeat, 40L, heartbeatTicks);
    }

    private void installNativeCommandOverrides() {
        if (!getConfig().getBoolean("override-bukkit-commands", true)) {
            getLogger().info("Native Bukkit command overrides are disabled by configuration.");
            bindNativeExecutorsWithoutOverride();
            return;
        }
        BukkitCommandOverride overrides;
        try {
            overrides = new BukkitCommandOverride(this);
            commandOverrides = overrides;
        } catch (IllegalStateException ex) {
            getLogger().severe(ex.getMessage());
            bindNativeExecutorsWithoutOverride();
            return;
        }
        for (String command : List.of("gamemode", "teleport", "fly", "tphere", "heal", "feed", "speed", "clear", "kill", "vanish")) {
            if (getCommand(command) != null) {
                getCommand(command).setExecutor(nativeCommands);
                getCommand(command).setTabCompleter(nativeCommands);
            }
        }
        overrides.override("gamemode", "gm");
        overrides.override("teleport", "tp");
        overrides.override("fly");
        overrides.override("tphere");
        overrides.override("heal");
        overrides.override("feed");
        overrides.override("speed");
        overrides.override("clear");
        overrides.override("kill");
        overrides.override("vanish", "v");
    }

    private void bindNativeExecutorsWithoutOverride() {
        for (String command : List.of("gamemode", "teleport", "fly", "tphere", "heal", "feed", "speed", "clear", "kill", "vanish")) {
            if (getCommand(command) != null) {
                getCommand(command).setExecutor(nativeCommands);
                getCommand(command).setTabCompleter(nativeCommands);
            }
        }
    }

    @Override
    public void onDisable() {
        if (xeonCommand != null) {
            for (Player player : Bukkit.getOnlinePlayers()) nativeCommands.clearVanish(player);
        }
        if (commandOverrides != null) {
            commandOverrides.restore();
            commandOverrides = null;
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
    }

    public BridgeAPI getBridge() { return api; }
    public String getServerId() { return serverId; }
    public boolean isDebug() { return debug; }

    public List<String> getKnownServers() {
        return List.copyOf(knownServers);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        try {
            BridgePacket packet = BridgePacket.decode(message, sharedSecret);
            if (!(packet.target().equalsIgnoreCase(serverId) || packet.target().equals("*") || packet.target().equalsIgnoreCase("proxy"))) {
                if (debug) getLogger().warning("Ignoring packet for another backend: " + packet.target());
                return;
            }
            if (debug) getLogger().info("RX " + packet.type() + " action=" + packet.action() + " from=" + packet.source());
            if (packet.type() == PacketType.PING) {
                send(player, BridgeMessages.pong(packet.requestId(), serverId, packet.source()));
                return;
            }
            api.handlePacket(packet);
        } catch (IOException | IllegalArgumentException ex) {
            getLogger().warning("Rejected invalid CrossBridge packet: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!event.getPlayer().isOnline()) return;
            try {
                send(event.getPlayer(), BridgeMessages.hello(System.nanoTime(), serverId));
                sendHeartbeat(event.getPlayer());
            } catch (Exception ex) {
                if (debug) getLogger().warning("Could not send CrossBridge hello: " + ex.getMessage());
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Player state is intentionally not persisted by CrossBridge.
    }

    private void sendHeartbeat() {
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) return;
        sendHeartbeat(carrier);
    }

    private void sendHeartbeat(Player carrier) {
        String payload = Bukkit.getOnlinePlayers().size() + "|" + Bukkit.getMaxPlayers()
                + "|" + safePayload(getServer().getMotd());
        api.sendToProxy(carrier, BridgeActions.SERVER_HEARTBEAT, payload);
    }

    private void updateServerList(String payload) {
        knownServers.clear();
        if (payload == null || payload.isBlank()) return;
        for (String raw : payload.split(",")) {
            String name = raw.trim();
            if (!name.isBlank()) knownServers.add(name);
        }
    }

    private void handlePlayerMessage(String payload) {
        String[] parts = payload.split("\\|", 2);
        if (parts.length != 2) return;
        try {
            Player target = Bukkit.getPlayer(java.util.UUID.fromString(parts[0]));
            if (target != null) target.sendMessage(ChatColor.translateAlternateColorCodes('&', parts[1]));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private String safePayload(String text) {
        if (text == null) return "";
        return text.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    private void send(Player player, BridgePacket packet) throws IOException {
        player.sendPluginMessage(this, CHANNEL, packet.encode(sharedSecret));
    }
}
