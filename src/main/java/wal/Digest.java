package wal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** Small md5 helper used to label snapshot contents in the UI. */
public final class Digest {
    private Digest() {}

    public static String md5Hex(Map<String, String> kv) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (Map.Entry<String, String> e : kv.entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
