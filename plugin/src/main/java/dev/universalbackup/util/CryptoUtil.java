package dev.universalbackup.util;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class CryptoUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private CryptoUtil() {}

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String randomCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }
}
