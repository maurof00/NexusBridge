package dev.crossbridge.spigot;

import dev.crossbridge.common.protocol.BridgePacket;

@FunctionalInterface
public interface BridgeHandler {
    void handle(BridgePacket packet);
}
