package dev.crossbridge.common.protocol;

/** Stable action names used by CrossBridge's built-in features. */
public final class BridgeActions {
    private BridgeActions() {}

    public static final String PLAYER_CONNECT = "player.connect";
    public static final String PLAYER_MESSAGE = "player.message";
    public static final String PLAYER_KICK = "player.kick";
    public static final String SERVER_HEARTBEAT = "server.heartbeat";
    public static final String SERVER_LIST = "server.list";
}
