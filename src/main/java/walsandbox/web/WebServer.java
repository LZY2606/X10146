package walsandbox.web;

import walsandbox.log.SegmentLog;
import walsandbox.log.Snapshot;
import walsandbox.recover.RecoveryResult;
import walsandbox.sandbox.CrashSpec;
import walsandbox.sandbox.Experiment;
import walsandbox.sandbox.Fault;
import walsandbox.sandbox.Materialization;
import walsandbox.sandbox.SandboxStore;
import walsandbox.util.HexDump;
import walsandbox.util.Json;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WebServer {

    private final SandboxStore store;
    private HttpServer server;

    public WebServer(SandboxStore store) {
        this.store = store;
    }

    public void start(String host, int port) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        server = HttpServer.create(addr, 0);
        server.createContext("/", this::route);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            handle(ex);
        } catch (Exception e) {
            sendJson(ex, 400, Map.of("error", e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if ("GET".equals(method) && path.equals("/")) {
            staticResource(ex, "/web/index.html", "text/html; charset=utf-8");
            return;
        }
        if ("GET".equals(method) && path.startsWith("/static/")) {
            String name = path.substring("/static/".length());
            String mime = name.endsWith(".css") ? "text/css; charset=utf-8"
                    : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : "application/octet-stream";
            staticResource(ex, "/web/" + name, mime);
            return;
        }

        if ("GET".equals(method) && path.equals("/api/experiments")) {
            List<Object> list = new ArrayList<>();
            for (Experiment e : store.list()) {
                list.add(ApiViews.base(e));
            }
            sendJson(ex, 200, Map.of("experiments", list));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/experiments")) {
            Map<String, Object> body = bodyMap(ex);
            Experiment exp = store.create(Json.str(body, "name"));
            sendJson(ex, 200, ApiViews.base(exp));
            return;
        }

        if (path.matches("/api/experiments/[^/]+/?")) {
            String id = path.split("/")[3];
            Experiment exp = store.get(id);
            if ("GET".equals(method)) {
                boolean recovered = Files.isRegularFile(
                        store.dataDir(id).getParent().resolve("recovery.json"));
                Materialization mat = readMaterialization(id);
                sendJson(ex, 200, ApiViews.summary(exp, mat, recovered));
                return;
            }
            if ("DELETE".equals(method)) {
                store.delete(id);
                sendJson(ex, 200, Map.of("deleted", id));
                return;
            }
        }

        if (path.matches("/api/experiments/[^/]+/events") && "POST".equals(method)) {
            String id = path.split("/")[3];
            Map<String, Object> body = bodyMap(ex);
            int eventId = store.addEvent(id, Json.str(body, "type"),
                    Json.str(body, "key"), Json.str(body, "value"),
                    asMap(body.get("fault")));
            sendJson(ex, 200, Map.of("eventId", eventId));
            return;
        }

        if (path.matches("/api/experiments/[^/]+/faults") && "POST".equals(method)) {
            String id = path.split("/")[3];
            Map<String, Object> body = bodyMap(ex);
            store.setFault(id, Json.integer(body, "eventId", 0), asMap(body.get("fault")));
            sendJson(ex, 200, Map.of("ok", true));
            return;
        }

        if (path.matches("/api/experiments/[^/]+/crash") && "POST".equals(method)) {
            String id = path.split("/")[3];
            Map<String, Object> body = bodyMap(ex);
            CrashSpec spec = switch (Json.str(body, "mode") == null
                    ? "NONE" : Json.str(body, "mode")) {
                case "AFTER_EVENT" -> CrashSpec.afterEvent(
                        Json.integer(body, "eventId", Json.integer(body, "value", 0)));
                case "AT_BYTE" -> CrashSpec.atByte(
                        Json.longValue(body, "value", Json.longValue(body, "offset", 0)));
                default -> CrashSpec.none();
            };
            store.setCrash(id, spec);
            sendJson(ex, 200, Map.of("ok", true));
            return;
        }

        if (path.matches("/api/experiments/[^/]+/materialize") && "POST".equals(method)) {
            String id = path.split("/")[3];
            Materialization mat = store.materialize(id);
            sendJson(ex, 200, ApiViews.materializationView(mat));
            return;
        }

        if (path.matches("/api/experiments/[^/]+/recover") && "POST".equals(method)) {
            String id = path.split("/")[3];
            RecoveryResult r = store.recover(id);
            sendJson(ex, 200, ApiViews.recoveryView(r));
            return;
        }

        if (path.matches("/api/experiments/[^/]+/reset") && "POST".equals(method)) {
            String id = path.split("/")[3];
            Materialization mat = store.resetPower(id);
            sendJson(ex, 200, ApiViews.materializationView(mat));
            return;
        }

        if (path.matches("/api/experiments/[^/]+/export") && "GET".equals(method)) {
            String id = path.split("/")[3];
            Path zip = store.exportZip(id);
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"" + id + ".zip\"");
            byte[] data = Files.readAllBytes(zip);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
            return;
        }

        if (path.matches("/api/experiments/[^/]+/files/[^/]+/hex")
                && "GET".equals(method)) {
            String[] parts = path.split("/");
            String id = parts[3];
            String fileName = parts[5]; // ["", "api", "experiments", id, "files", name, "hex"]
            Path file = safeDataFile(id, fileName);
            if (!Files.isRegularFile(file)) {
                sendJson(ex, 404, Map.of("error", "文件不存在: " + fileName));
                return;
            }
            byte[] data = Files.readAllBytes(file);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileName", fileName);
            m.put("size", data.length);
            m.put("hex", HexDump.dump(data));
            sendJson(ex, 200, m);
            return;
        }

        if (path.matches("/api/experiments/[^/]+/files") && "GET".equals(method)) {
            String id = path.split("/")[3];
            List<Object> files = new ArrayList<>();
            Path dataDir = store.dataDir(id);
            if (Files.isDirectory(dataDir)) {
                try (var stream = Files.list(dataDir)) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        Map<String, Object> fm = new LinkedHashMap<>();
                        fm.put("name", p.getFileName().toString());
                        fm.put("size", Files.size(p));
                        fm.put("snapshot", p.getFileName().toString()
                                .equals(Snapshot.FILE_NAME));
                        fm.put("tmp", p.getFileName().toString()
                                .endsWith(Snapshot.TMP_SUFFIX));
                        fm.put("segment", p.getFileName().toString()
                                .startsWith(SegmentLog.PREFIX));
                        files.add(fm);
                    }
                }
            }
            sendJson(ex, 200, Map.of("files", files));
            return;
        }

        if (path.equals("/api/import") && "POST".equals(method)) {
            Path tmp = Files.createTempFile("wal-import", ".zip");
            try (InputStream is = ex.getRequestBody(); OutputStream os = Files.newOutputStream(tmp)) {
                is.transferTo(os);
            }
            String id = store.importZip(tmp);
            sendJson(ex, 200, Map.of("id", id));
            return;
        }

        sendJson(ex, 404, Map.of("error", "not found: " + method + " " + path));
    }

    private Path safeDataFile(String id, String fileName) {
        Path dataDir = store.dataDir(id).toAbsolutePath().normalize();
        Path file = dataDir.resolve(fileName).normalize();
        if (!file.startsWith(dataDir)) {
            throw new IllegalArgumentException("非法文件名");
        }
        return file;
    }

    private Materialization readMaterialization(String id) {
        Path f = store.dataDir(id).getParent().resolve("materialization.json");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            Map<String, Object> m = Json.object(Files.readString(f));
            return new Materialization(Boolean.TRUE.equals(m.get("crashed")),
                    String.valueOf(m.getOrDefault("crashReason", "")),
                    Json.integer(m, "eventsApplied", 0),
                    Json.longValue(m, "durableBytes", 0), List.of());
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, Object> bodyMap(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return Map.of();
            }
            Map<String, Object> m = Json.object(text);
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private void staticResource(HttpExchange ex, String resource, String mime)
            throws IOException {
        try (InputStream is = WebServer.class.getResourceAsStream(resource)) {
            if (is == null) {
                sendJson(ex, 404, Map.of("error", "missing resource " + resource));
                return;
            }
            byte[] data = is.readAllBytes();
            Headers headers = ex.getResponseHeaders();
            headers.set("Content-Type", mime);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] data = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
