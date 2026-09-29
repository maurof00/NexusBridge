package dev.crossbridge.common.protocol;

public enum PacketType {
    HELLO(1),
    CUSTOM(2),
    RESPONSE(3),
    PING(4),
    PONG(5);

    private final int id;

    PacketType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static PacketType fromId(int id) {
        for (PacketType type : values()) {
            if (type.id == id) return type;
        }
        throw new IllegalArgumentException("Unknown packet type: " + id);
    }
}
