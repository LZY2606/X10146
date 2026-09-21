package wal;

/** Classic offset / hex-bytes / ascii rendering of the durable byte prefix. */
public final class HexDump {

    private HexDump() {}

    public static String dump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < data.length; off += 16) {
            sb.append(String.format("%08x  ", off));
            String ascii = "";
            for (int col = 0; col < 16; col++) {
                int idx = off + col;
                if (idx < data.length) {
                    byte b = data[idx];
                    sb.append(String.format("%02x ", b));
                    char c = (char) (b & 0xFF);
                    ascii += (c >= 32 && c < 127) ? c : '.';
                } else {
                    sb.append("   ");
                    ascii += " ";
                }
                if (col == 7) sb.append(' ');
            }
            sb.append(' ').append('|').append(ascii).append('|').append('\n');
        }
        if (data.length == 0) {
            sb.append("（空文件 / 0 字节）\n");
        }
        return sb.toString();
    }
}
