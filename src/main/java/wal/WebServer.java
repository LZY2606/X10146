package wal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local single-page HTTP UI for the WAL recovery sandbox.
 *
 * The page lets the user edit transactions, append records one at a time,
 * inspect raw hex frames, choose a crash point / fault switch, run crash
 * recovery and compare the recovered key/value state with the no-crash
 * expectation.
 */
public final class WebServer {

    private final ExperimentStore store;
    private final Gson gson = new GsonBuilder().create();
    private HttpServer server;

    public WebServer(Path dataDir) {
        this.store = new ExperimentStore(dataDir);
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::staticFile);
        server.createContext("/api/", this::api);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) {
            send(ex, 404, "not found", "text/plain");
            return;
        }
        String resource = "/web" + path;
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "not found", "text/plain");
                return;
            }
            byte[] body = in.readAllBytes();
            String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : path.endsWith(".css") ? "text/css; charset=utf-8"
                    : "application/octet-stream";
            sendBytes(ex, 200, body, type);
        }
    }

    private void api(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage() == null
                    ? "bad request" : e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", String.valueOf(e)));
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        // Import streams the (potentially large) request body, so handle it
        // before reading/closing the body for the JSON routes.
        if (path.equals("/api/import") && method.equals("POST")) {
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            byte[] raw = ex.getRequestBody().readAllBytes();
            java.io.ByteArrayInputStream zipIn = new java.io.ByteArrayInputStream(raw);
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                zipIn = new java.io.ByteArrayInputStream(
                        extractMultipartFile(raw, contentType));
            }
            Experiment imported = store.importZip(zipIn);
            sendJson(ex, 200, imported);
            return;
        }

        String bodyStr = readBody(ex);

        switch (path) {
            case "/api/experiments":
                if (method.equals("GET")) {
                    sendJson(ex, 200, store.list());
                } else if (method.equals("POST")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = gson.fromJson(bodyStr, Map.class);
                    String name = body == null ? null : (String) body.get("name");
                    sendJson(ex, 200, store.create(name));
                } else {
                    methodNotAllowed(ex);
                }
                return;
            default:
                break;
        }

        String prefix = "/api/experiments/";
        if (path.startsWith(prefix)) {
            String rest = path.substring(prefix.length());
            String[] parts = rest.split("/", 2);
            String id = parts[0];
            String action = parts.length > 1 ? parts[1] : "";
            Experiment experiment = store.get(id);
            if (experiment == null) {
                sendJson(ex, 404, Map.of("error", "实验不存在"));
                return;
            }
            if (method.equals("GET") && action.isEmpty()) {
                sendJson(ex, 200, experiment);
                return;
            }
            if (method.equals("DELETE") && action.isEmpty()) {
                store.delete(id);
                sendJson(ex, 200, Map.of("ok", true));
                return;
            }
            if (method.equals("PUT") && action.equals("script")) {
                Experiment incoming = gson.fromJson(bodyStr, Experiment.class);
                applyScript(experiment, incoming);
                store.save(experiment);
                sendJson(ex, 200, experiment);
                return;
            }
            if (method.equals("POST") && action.equals("append")) {
                ScriptOp op = gson.fromJson(bodyStr, ScriptOp.class);
                experiment.script.add(op);
                experiment.revision++;
                store.save(experiment);
                sendJson(ex, 200, experiment);
                return;
            }
            if (method.equals("POST") && action.equals("materialise")) {
                SimulationResult result = store.materialise(id);
                sendJson(ex, 200, materialisedView(id, result));
                return;
            }
            if (method.equals("POST") && action.equals("recover")) {
                RecoveryReport report = store.recover(id);
                RecoveryReport expected = ExpectedState.compute(
                        store.mediaDir("__expected__"), experiment.script);
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("report", report);
                view.put("expectedKv", expected.kv);
                view.put("matchesExpected", report.kv.equals(expected.kv));
                sendJson(ex, 200, view);
                return;
            }
            if (method.equals("GET") && action.equals("media")) {
                sendJson(ex, 200, mediaView(id));
                return;
            }
            if (method.equals("GET") && action.equals("export")) {
                exportZip(ex, id);
                return;
            }
        }

        sendJson(ex, 404, Map.of("error", "no such api route: " + path));
    }

    private void applyScript(Experiment target, Experiment incoming) {
        target.script.clear();
        if (incoming.script != null) {
            target.script.addAll(incoming.script);
        }
        if (incoming.name != null) {
            target.name = incoming.name;
        }
        if (incoming.plan != null) {
            target.plan = incoming.plan;
        }
        target.revision++;
    }

    private Map<String, Object> materialisedView(String id, SimulationResult result)
            throws IOException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("simulation", result);
        view.put("media", mediaView(id));
        return view;
    }

    private Map<String, Object> mediaView(String id) throws IOException {
        Map<String, Object> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : store.rawFiles(id).entrySet()) {
            Map<String, Object> file = new LinkedHashMap<>();
            byte[] data = e.getValue();
            file.put("size", data.length);
            file.put("hex", HexDump.dump(data));
            file.put("scan", scanView(e.getKey(), data));
            files.put(e.getKey(), file);
        }
        return files;
    }

    private Object scanView(String name, byte[] data) {
        List<Map<String, Object>> records = new java.util.ArrayList<>();
        if (name.endsWith(Journal.SEGMENT_SUFFIX)
                && data.length >= SegmentHeader.SIZE
                && SegmentHeader.decode(data) != null) {
            int cursor = SegmentHeader.SIZE;
            while (cursor < data.length) {
                ScanRecord rec = LogScanner.nextRecord(data, cursor);
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("offset", rec.offset);
                map.put("outcome", rec.outcome.name());
                if (rec.frame != null) {
                    map.put("type", rec.frame.type().name());
                    map.put("txnId", rec.frame.txnId());
                    map.put("seqNo", rec.frame.seqNo());
                    map.put("size", rec.consumed);
                }
                if (rec.declaredPayloadLength >= 0) {
                    map.put("declaredPayloadLength", rec.declaredPayloadLength);
                }
                records.add(map);
                if (rec.outcome == ScanOutcome.VALID) {
                    cursor = rec.offset + rec.consumed;
                } else {
                    break;
                }
            }
        }
        return records;
    }

    private void exportZip(HttpExchange ex, String id) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/zip");
        ex.getResponseHeaders().add("Content-Disposition",
                "attachment; filename=\"" + id + ".zip\"");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            store.exportZip(id, out);
        }
    }

    /** Extracts the first file part of a multipart/form-data body. */
    private static byte[] extractMultipartFile(byte[] body, String contentType) {
        String boundary = null;
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                boundary = part.substring("boundary=".length());
            }
        }
        if (boundary == null) throw new IllegalArgumentException("缺少 multipart boundary");
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        int start = indexOf(body, marker, 0);
        if (start < 0) throw new IllegalArgumentException("multipart 中没有分隔行");
        int headerEnd = indexOf(body, new byte[] {13, 10, 13, 10}, start);
        if (headerEnd < 0) throw new IllegalArgumentException("multipart 头不完整");
        int dataStart = headerEnd + 4;
        int dataEnd = indexOf(body, marker, dataStart);
        if (dataEnd < 0) throw new IllegalArgumentException("multipart 尾部分隔缺失");
        int end = dataEnd - 2; // strip preceding CRLF
        if (end < dataStart) end = dataStart;
        return java.util.Arrays.copyOfRange(body, dataStart, end);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void methodNotAllowed(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(405, -1);
        ex.close();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] body = gson.toJson(obj).getBytes(StandardCharsets.UTF_8);
        sendBytes(ex, status, body, "application/json; charset=utf-8");
    }

    private void sendBytes(HttpExchange ex, int status, byte[] body, String type)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void send(HttpExchange ex, int status, String text, String type)
            throws IOException {
        sendBytes(ex, status, text.getBytes(StandardCharsets.UTF_8), type);
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5226;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                default:
                    throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        Path dataDir = Path.of("sandbox-data");
        WebServer web = new WebServer(dataDir);
        web.start(host, port);
        System.out.println("WAL 恢复沙盒 已启动: http://" + host + ":" + web.port());
        System.out.println("数据目录: " + dataDir.toAbsolutePath());
    }
}
