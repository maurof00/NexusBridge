package dev.crossbridge.common.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

public final class CryptoUtil {
    private static final String ALGORITHM = "HmacSHA256";

    private CryptoUtil() {}

    public static byte[] sign(byte[] data, String secret) {
        if (secret == null || secret.isBlank()) return new byte[0];
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to initialize HMAC", ex);
        }
    }

    public static boolean verify(byte[] data, byte[] signature, String secret) {
        if (secret == null || secret.isBlank()) return signature.length == 0;
        byte[] expected = sign(data, secret);
        return MessageDigest.isEqual(expected, signature);
    }
}
