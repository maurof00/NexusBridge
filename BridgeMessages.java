package dev.crossbridge.common.protocol;

public final class BridgeMessages {
    private BridgeMessages() {}

    public static BridgePacket hello(long id, String server) {
        return new BridgePacket(PacketType.HELLO, id, server, "proxy", "hello", "CrossBridge/1");
    }

    public static BridgePacket custom(long id, String source, String target, String action, String payload) {
        return new BridgePacket(PacketType.CUSTOM, id, source, target, action, payload);
    }

    public static BridgePacket response(long id, String source, String target, String action, String payload) {
        return new BridgePacket(PacketType.RESPONSE, id, source, target, action, payload);
    }

    public static BridgePacket ping(long id, String source) {
        return new BridgePacket(PacketType.PING, id, source, "proxy", "ping", "");
    }

    public static BridgePacket pong(long id, String source, String target) {
        return new BridgePacket(PacketType.PONG, id, source, target, "pong", "");
    }
}
