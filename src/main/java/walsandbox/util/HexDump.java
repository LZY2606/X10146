package walsandbox.util;

public final class HexDump {

    private HexDump() {
    }

    /** Classic offset / hex / ascii dump, 16 bytes per line. */
    public static String dump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < data.length; off += 16) {
            sb.append(String.format("%08x  ", off));
            for (int i = 0; i < 16; i++) {
                if (off + i < data.length) {
                    sb.append(String.format("%02x ", data[off + i] & 0xFF));
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(' ');
            for (int i = 0; i < 16 && off + i < data.length; i++) {
                int c = data[off + i] & 0xFF;
                sb.append(c >= 32 && c < 127 ? (char) c : '.');
            }
            sb.append('\n');
        }
        if (data.length == 0) {
            sb.append("(空)\n");
        }
        return sb.toString();
    }
}
