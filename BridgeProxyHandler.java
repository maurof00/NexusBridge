package dev.crossbridge.bungee;

import dev.crossbridge.common.protocol.BridgePacket;

@FunctionalInterface
public interface BridgeProxyHandler {
    void handle(BridgePacket packet);
}
