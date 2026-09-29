package dev.crossbridge.common.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

public record BridgePacket(
        PacketType type,
        long requestId,
        String source,
        String target,
        String action,
        String payload
) {
    public static final int VERSION = 2;
    private static final int MAGIC = 0x43425031; // CBP1
    private static final int MAX_PACKET_BYTES = 32 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 24 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 64;

    public BridgePacket {
        type = type == null ? PacketType.CUSTOM : type;
        source = source == null ? "unknown" : source;
        target = target == null ? "proxy" : target;
        action = action == null ? "" : action;
        payload = payload == null ? "" : payload;
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload too large");
        }
    }

    /** Unsigned encoding used for tests/tools. Runtime traffic should call {@link #encode(String)}. */
    public byte[] encode() throws IOException {
        return encodeInternal(null);
    }

    /** Encodes this packet and signs it with the shared secret when one is configured. */
    public byte[] encode(String sharedSecret) throws IOException {
        return encodeInternal(sharedSecret);
    }

    private byte[] encodeInternal(String sharedSecret) throws IOException {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(256 + payloadBytes.length);
        DataOutputStream body = new DataOutputStream(bodyBytes);
        body.writeInt(MAGIC);
        body.writeByte(VERSION);
        body.writeByte(type.id());
        body.writeLong(requestId);
        writeString(body, source);
        writeString(body, target);
        writeString(body, action);
        body.writeInt(payloadBytes.length);
        body.write(payloadBytes);
        body.flush();

        byte[] bodyData = bodyBytes.toByteArray();
        byte[] signature = CryptoUtil.sign(bodyData, sharedSecret);
        if (signature.length > MAX_SIGNATURE_BYTES) throw new IOException("Signature too long");

        ByteArrayOutputStream fullBytes = new ByteArrayOutputStream(bodyData.length + 1 + signature.length);
        DataOutputStream out = new DataOutputStream(fullBytes);
        out.write(bodyData);
        out.writeByte(signature.length);
        out.write(signature);
        out.flush();
        if (fullBytes.size() > MAX_PACKET_BYTES) throw new IOException("Packet too large");
        return fullBytes.toByteArray();
    }

    public static BridgePacket decode(byte[] data) throws IOException {
        return decodeInternal(data, null, false);
    }

    /** Decodes and verifies an authenticated packet when {@code sharedSecret} is set. */
    public static BridgePacket decode(byte[] data, String sharedSecret) throws IOException {
        return decodeInternal(data, sharedSecret, sharedSecret != null && !sharedSecret.isBlank());
    }

    private static BridgePacket decodeInternal(byte[] data, String sharedSecret, boolean requireSignature) throws IOException {
        if (data == null || data.length < 1 || data.length > MAX_PACKET_BYTES) {
            throw new IOException("Invalid packet size");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid CrossBridge packet magic");
            if (in.readUnsignedByte() != VERSION) throw new IOException("Unsupported CrossBridge protocol version");
            PacketType type = PacketType.fromId(in.readUnsignedByte());
            long requestId = in.readLong();
            String source = readString(in, 256);
            String target = readString(in, 256);
            String action = readString(in, 256);
            int payloadLength = in.readInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES || payloadLength > in.available() - 1) {
                throw new IOException("Invalid payload length: " + payloadLength);
            }
            byte[] payload = in.readNBytes(payloadLength);
            int bodyLength = data.length - in.available();
            byte[] signatureBody = java.util.Arrays.copyOf(data, bodyLength);
            int signatureLength = in.readUnsignedByte();
            if (signatureLength > MAX_SIGNATURE_BYTES || signatureLength > in.available()) {
                throw new IOException("Invalid signature length: " + signatureLength);
            }
            byte[] signature = in.readNBytes(signatureLength);
            if (in.available() != 0) throw new IOException("Trailing bytes in packet");
            if (requireSignature && !CryptoUtil.verify(signatureBody, signature, sharedSecret)) {
                throw new IOException("Invalid CrossBridge signature");
            }
            if (!requireSignature && sharedSecret != null && !sharedSecret.isBlank() && signatureLength > 0
                    && !CryptoUtil.verify(signatureBody, signature, sharedSecret)) {
                throw new IOException("Invalid CrossBridge signature");
            }
            return new BridgePacket(type, requestId, source, target, action,
                    new String(payload, StandardCharsets.UTF_8));
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > 0xFFFF) throw new IOException("String too long");
        out.writeShort(data.length);
        out.write(data);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length > maxBytes || length > in.available()) throw new IOException("String too long");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
