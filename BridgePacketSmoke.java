package dev.crossbridge.common.protocol;

import java.util.Arrays;

public final class BridgePacketSmoke {
    public static void main(String[] args) throws Exception {
        String secret = "test-secret-1234567890";
        BridgePacket source = BridgeMessages.custom(42L, "lobby", "survival", "hello", "ciao");
        BridgePacket decoded = BridgePacket.decode(source.encode(secret), secret);
        if (!source.equals(decoded)) throw new AssertionError("round-trip mismatch");
        byte[] tampered = source.encode(secret);
        tampered[tampered.length - 1] ^= 1;
        try {
            BridgePacket.decode(tampered, secret);
            throw new AssertionError("tamper accepted");
        } catch (java.io.IOException expected) {
            // expected
        }
        if (Arrays.equals(source.encode(secret), source.encode("other-secret"))) {
            throw new AssertionError("different secrets produced the same signature");
        }
        System.out.println("BRIDGE_PACKET_HMAC_OK");
    }
}
