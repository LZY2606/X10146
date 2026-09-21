package walsandbox.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny dependency-free JSON parser/writer, enough for this sandbox's API. */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.value();
        p.skipWs();
        if (p.pos < p.text.length()) {
            throw new IllegalArgumentException("trailing garbage at " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, value);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        return sb.toString();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static int integer(Map<String, Object> m, String key, int dflt) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null ? dflt : Integer.parseInt(v.toString());
    }

    public static long longValue(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return v == null ? dflt : Long.parseLong(v.toString());
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listOfObjects(Map<String, Object> m, String key) {
        Object v = m.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    private static void writeInto(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof String str) {
            writeString(sb, str);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey().toString());
                sb.append(':');
                writeInto(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeInto(sb, o);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writePretty(StringBuilder sb, Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                indent(sb, depth + 1);
                writeString(sb, e.getKey().toString());
                sb.append(": ");
                writePretty(sb, e.getValue(), depth + 1);
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                indent(sb, depth + 1);
                writePretty(sb, o, depth + 1);
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append(']');
        } else {
            writeInto(sb, value);
        }
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Object value() {
            skipWs();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't':
                case 'f': return bool();
                case 'n': return nul();
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                m.put(key, value());
                skipWs();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or } at " + pos);
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or ] at " + pos);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> sb.append((char) Integer.parseInt(
                                text.substring(pos, pos + 4), 16));
                        default -> throw new IllegalArgumentException("bad escape " + e);
                    }
                    if (e == 'u') {
                        pos += 4;
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Boolean bool() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("bad literal at " + pos);
        }

        private Object nul() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("bad literal at " + pos);
        }

        private Number number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length() && "0123456789.eE+-"
                    .indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String token = text.substring(start, pos);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        private char peek() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            return text.charAt(pos);
        }

        private char next() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            return text.charAt(pos++);
        }

        private void expect(char c) {
            char actual = next();
            if (actual != c) {
                throw new IllegalArgumentException("expected " + c + " got " + actual
                        + " at " + (pos - 1));
            }
        }
    }
}
