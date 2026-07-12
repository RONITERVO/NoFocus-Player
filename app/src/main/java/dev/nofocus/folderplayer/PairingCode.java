package dev.nofocus.folderplayer;

import java.security.SecureRandom;

final class PairingCode {
    private static final char[] BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private PairingCode() {
    }

    static String generate() {
        SecureRandom random = new SecureRandom();
        char[] result = new char[16];
        for (int i = 0; i < result.length; i++) {
            result[i] = BASE32[random.nextInt(BASE32.length)];
        }
        return new String(result);
    }

    static String display(String value) {
        if (value == null || value.length() != 16) {
            return value == null ? "" : value;
        }
        return value.substring(0, 4) + "-" + value.substring(4, 8) + "-"
                + value.substring(8, 12) + "-" + value.substring(12);
    }
}
