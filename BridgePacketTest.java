package dev.crossbridge.common.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgePacketTest {
    @Test
    void roundTrip() throws Exception {
        BridgePacket packet = BridgeMessages.custom(42, "lobby", "survival", "chat", "hello");
        BridgePacket decoded = BridgePacket.decode(packet.encode());
        assertEquals(packet, decoded);
    }
}
