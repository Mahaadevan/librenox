package mcht;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** JDK-only HTTP server: static web UI + JSON API. Loopback-only by default. */
final class WebServer {
    private final Registry reg;
    private final HttpServer server;
    private final int port;
    private final boolean loopback;

    WebServer(Registry reg, String bind, int port) throws IOException {
        this.reg = reg;
        this.port = port;
        this.loopback = bind.equals("127.0.0.1") || bind.equals("localhost") || bind.equals("::1");
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http");
            t.setDaemon(true);
            return t;
        }));
    }

    void start() { server.start(); }

    // ------------------------------------------------------------ plumbing
    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!hostAllowed(ex)) { send(ex, 403, "text/plain; charset=utf-8", "Forbidden".getBytes()); return; }
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            if (path.startsWith("/api/")) {
                if (method.equals("POST") && !postAllowed(ex)) { json(ex, 403, Map.of("error", "Cross-site request blocked.")); return; }
                api(ex, method, path);
            } else if (method.equals("GET") || method.equals("HEAD")) staticFile(ex, path);
            else send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes());
        } catch (ApiError e) {
            json(ex, e.code, Map.of("error", e.getMessage()));
        } catch (Throwable t) {
            json(ex, 500, Map.of("error", Util.msg(t)));
        } finally {
            ex.close();
        }
    }

    private boolean hostAllowed(HttpExchange ex) {
        if (!loopback) return true;
        String h = ex.getRequestHeaders().getFirst("Host");
        if (h == null) return false;
        h = h.toLowerCase();
        return h.equals("localhost:" + port) || h.equals("127.0.0.1:" + port) || h.equals("[::1]:" + port);
    }

    private boolean postAllowed(HttpExchange ex) {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        boolean okType = (ct != null && ct.toLowerCase().startsWith("application/json")) || ex.getRequestHeaders().getFirst("X-MCHT") != null;
        if (!okType) return false;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        try {
            return Objects.equals(URI.create(origin).getAuthority(), ex.getRequestHeaders().getFirst("Host"));
        } catch (Exception e) {
            return false;
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' https://cdn.modrinth.com data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
        if (body.length == 0 || ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static void json(HttpExchange ex, int code, Object o) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.stringify(o).getBytes(StandardCharsets.UTF_8));
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) { send(ex, 404, "text/plain", "Not found".getBytes()); return; }
        try (InputStream in = WebServer.class.getResourceAsStream("/web" + path)) {
            if (in == null) { send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes()); return; }
            String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".css") ? "text/css; charset=utf-8"
                    : path.endsWith(".js") ? "text/javascript; charset=utf-8" : path.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
            send(ex, 200, type, in.readAllBytes());
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            q.put(k, i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(24 << 20);
        if (b.length == 0) return new HashMap<>();
        try {
            return Json.obj(Json.parse(new String(b, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new ApiError(400, "Invalid JSON body.");
        }
    }

    private static Map<String, Object> ok() { return Map.of("ok", true); }

    private static void file(HttpExchange ex, String type, String name, Path p) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Content-Disposition", "attachment; filename=\"" + name.replaceAll("[\"\\r\\n\\\\]", "_") + "\"");
        ex.sendResponseHeaders(200, Files.size(p));
        try (OutputStream os = ex.getResponseBody()) { Files.copy(p, os); }
    }

    // ------------------------------------------------------------ API
    private void api(HttpExchange ex, String method, String path) throws Exception {
        Map<String, String> q = query(ex);
        boolean raw = path.equals("/api/files/upload");
        Map<String, Object> b = method.equals("POST") && !raw ? body(ex) : Map.of();
        String key = method + " " + path;
        // routes that need no server
        Object out;
        switch (key) {
            case "GET /api/servers" -> {
                List<Object> l = new ArrayList<>();
                for (Manager m : reg.all()) l.add(m.status());
                json(ex, 200, l);
                return;
            }
            case "POST /api/servers/create" -> {
                Manager m = reg.create(b);
                json(ex, 200, Map.of("id", m.cfg.id));
                return;
            }
            case "POST /api/servers/delete" -> {
                reg.delete(q.getOrDefault("id", ""), Json.str(b.get("confirm")));
                json(ex, 200, ok());
                return;
            }
            case "GET /api/software/versions" -> {
                String sw = q.getOrDefault("software", "");
                if (!Config.SOFTWARE.contains(sw) || sw.equals("custom")) throw new ApiError(400, "Unknown server software.");
                json(ex, 200, Providers.versions(sw));
                return;
            }
            case "GET /api/dirs" -> {
                List<Map<String, Object>> l = new ArrayList<>();
                Object[][] rows = {{"Tool data", Dirs.BASE}, {"All servers", Dirs.SERVERS}, {"Downloaded tools", Dirs.BIN}, {"Java runtime", Dirs.RUNTIME}, {"Temporary files", Dirs.TMP}};
                for (Object[] r : rows) l.add(Map.of("label", r[0], "path", r[1].toString()));
                json(ex, 200, l);
                return;
            }
            case "POST /api/quit" -> {
                json(ex, 200, ok());
                Thread t = new Thread(() -> { reg.shutdown(); System.exit(0); }, "quit");
                t.start();
                return;
            }
            default -> { }
        }
        if (key.equals("GET /api/checks") && !q.containsKey("id")) {
            String java = JavaRt.locate(0);
            json(ex, 200, List.of(Map.of("label", "Java runtime", "ok", java != null, "detail", java != null ? java : "Install Java or let the tool download it"),
                    Map.of("label", "Data folder", "ok", Files.isWritable(Dirs.BASE), "detail", Dirs.BASE.toString())));
            return;
        }
        Manager m = reg.get(q.get("id"));
        switch (key) {
            case "GET /api/logs" -> {
                long after = 0;
                try { after = Long.parseLong(q.getOrDefault("after", "0")); } catch (NumberFormatException ignored) {}
                if (after > m.lastLogId()) after = 0;
                List<Map<String, Object>> rows = new ArrayList<>();
                long last = after;
                for (Manager.Log l : m.logsAfter(after)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", l.id()); r.put("ts", l.ts()); r.put("kind", l.kind()); r.put("lvl", l.lvl()); r.put("text", l.text());
                    rows.add(r);
                    last = l.id();
                }
                out = Map.of("logs", rows, "last", last);
            }
            case "POST /api/start" -> { m.start(); out = ok(); }
            case "POST /api/stop" -> { m.stop(); out = ok(); }
            case "POST /api/restart" -> { m.restart(); out = ok(); }
            case "POST /api/command" -> { m.command(Json.str(b.get("cmd"))); out = ok(); }
            case "POST /api/eula" -> {
                if (!Boolean.TRUE.equals(b.get("accept"))) throw new ApiError(400, "EULA not accepted.");
                m.acceptEula();
                out = ok();
            }
            case "GET /api/config" -> out = m.cfg.toMap();
            case "POST /api/config" -> { m.cfg.apply(b); out = m.cfg.toMap(); }
            case "GET /api/properties" -> out = Props.read(m.props);
            case "POST /api/properties" -> {
                Map<String, String> ch = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : Json.obj(b.get("values")).entrySet()) ch.put(e.getKey(), Json.str(e.getValue()));
                Props.update(m.props, ch);
                out = ok();
            }
            case "POST /api/software" -> { m.changeSoftware(Json.str(b.get("software")), Json.str(b.get("version")), !Boolean.FALSE.equals(b.get("backup"))); out = ok(); }
            case "POST /api/software/update" -> { m.updateBuild(); out = ok(); }
            case "POST /api/tunnel" -> { m.tunnelStart(Json.str(b.get("provider"))); out = ok(); }
            case "POST /api/tunnel/stop" -> { m.tunnelStop(); out = ok(); }
            case "POST /api/tunnel/address" -> { m.setCustomAddress(Json.str(b.get("address"))); out = ok(); }
            case "GET /api/ext/search" -> out = m.search(q.getOrDefault("type", ""), q.getOrDefault("q", ""));
            case "GET /api/ext/installed" -> out = m.installed(q.getOrDefault("type", ""));
            case "POST /api/ext/install" -> { m.install(Json.str(b.get("type")), Json.str(b.get("project_id")), Json.str(b.get("title"))); out = ok(); }
            case "POST /api/ext/remove" -> { m.remove(Json.str(b.get("type")), Json.str(b.get("name"))); out = ok(); }
            case "POST /api/ext/toggle" -> { m.toggle(Json.str(b.get("type")), Json.str(b.get("name"))); out = ok(); }
            case "POST /api/geyser" -> { m.geyser(); out = ok(); }
            case "POST /api/backup" -> { m.backup(); out = ok(); }
            case "GET /api/backups" -> out = m.backups();
            case "POST /api/backups/delete" -> { Files.deleteIfExists(m.backupFile(Json.str(b.get("name")))); out = ok(); }
            case "POST /api/backups/restore" -> { m.restore(Json.str(b.get("name"))); out = ok(); }
            case "GET /api/backups/download" -> {
                Path p = m.backupFile(q.getOrDefault("name", ""));
                file(ex, "application/zip", p.getFileName().toString(), p);
                return;
            }
            case "GET /api/access" -> out = Access.list(m, q.getOrDefault("kind", ""));
            case "POST /api/access/add" -> { Access.add(m, Json.str(b.get("kind")), Json.str(b.get("value"))); out = ok(); }
            case "POST /api/access/remove" -> { Access.remove(m, Json.str(b.get("kind")), Json.str(b.get("value"))); out = ok(); }
            case "GET /api/files" -> out = FileApi.list(m.dir, q.getOrDefault("path", ""));
            case "GET /api/file" -> out = Map.of("content", FileApi.read(m.dir, q.getOrDefault("path", "")));
            case "POST /api/file" -> { FileApi.write(m.dir, Json.str(b.get("path")), Json.str(b.get("content"))); out = ok(); }
            case "POST /api/files/mkdir" -> { FileApi.mkdir(m.dir, Json.str(b.get("path"))); out = ok(); }
            case "POST /api/files/delete" -> { FileApi.delete(m.dir, Json.str(b.get("path"))); out = ok(); }
            case "POST /api/files/rename" -> { FileApi.rename(m.dir, Json.str(b.get("path")), Json.str(b.get("name"))); out = ok(); }
            case "POST /api/files/unzip" -> { FileApi.unzip(m.dir, Json.str(b.get("path"))); out = ok(); }
            case "POST /api/files/upload" -> {
                if (ex.getRequestHeaders().getFirst("X-MCHT") == null) throw new ApiError(403, "Missing header.");
                Path folder = FileApi.res(m.dir, q.getOrDefault("path", ""));
                if (!Files.isDirectory(folder)) throw new ApiError(404, "Folder not found.");
                Path target = Util.safeChild(folder, q.getOrDefault("name", ""));
                FileApi.res(m.dir, m.dir.relativize(target).toString());
                Path part = target.resolveSibling(target.getFileName() + ".part");
                try (InputStream in = ex.getRequestBody()) { Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING); }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                out = ok();
            }
            case "GET /api/files/download" -> {
                Path p = FileApi.res(m.dir, q.getOrDefault("path", ""));
                if (!Files.isRegularFile(p)) throw new ApiError(404, "File not found.");
                file(ex, "application/octet-stream", p.getFileName().toString(), p);
                return;
            }
            case "GET /api/worlds" -> out = FileApi.worlds(m.dir);
            case "POST /api/worlds/delete" -> {
                if (!m.state.equals("offline")) throw new ApiError(409, "Stop the server before deleting a world.");
                Path w = FileApi.res(m.dir, Json.str(b.get("name")));
                if (!Files.exists(w.resolve("level.dat"))) throw new ApiError(400, "That folder is not a world.");
                Util.deleteTree(w);
                m.host("Deleted world " + w.getFileName() + ".");
                out = ok();
            }
            case "GET /api/worlds/download" -> {
                Path w = FileApi.res(m.dir, q.getOrDefault("name", ""));
                if (!Files.exists(w.resolve("level.dat"))) throw new ApiError(404, "World not found.");
                Headers h = ex.getResponseHeaders();
                h.set("Content-Type", "application/zip");
                h.set("Content-Disposition", "attachment; filename=\"" + w.getFileName().toString().replaceAll("[\"\\r\\n\\\\]", "_") + ".zip\"");
                ex.sendResponseHeaders(200, 0);
                FileApi.zipDir(w, ex.getResponseBody());
                return;
            }
            case "GET /api/logfiles" -> out = FileApi.logFiles(m.dir);
            case "GET /api/logfile" -> out = Map.of("text", FileApi.logText(m.dir, q.getOrDefault("name", "latest.log")));
            case "GET /api/icon" -> {
                Path p = Icons.file(m.dir);
                if (!Files.exists(p)) throw new ApiError(404, "No icon.");
                send(ex, 200, "image/png", Files.readAllBytes(p));
                return;
            }
            case "GET /api/icon/candidates" -> out = Icons.candidates(m.dir);
            case "GET /api/icon/candidate" -> {
                send(ex, 200, "image/png", Icons.candidateBytes(m.dir, q.getOrDefault("source", ""), q.getOrDefault("entry", "")));
                return;
            }
            case "POST /api/icon" -> {
                byte[] data;
                if (b.containsKey("source")) data = Icons.candidateBytes(m.dir, Json.str(b.get("source")), Json.str(b.get("entry")));
                else if (b.containsKey("path")) {
                    Path p = FileApi.res(m.dir, Json.str(b.get("path")));
                    if (!Files.isRegularFile(p) || Files.size(p) > 8_000_000) throw new ApiError(400, "Pick an image file under 8 MB.");
                    data = Files.readAllBytes(p);
                } else {
                    String d = Json.str(b.get("data"));
                    try { data = Base64.getMimeDecoder().decode(d.substring(d.indexOf(',') + 1)); } catch (IllegalArgumentException e) { throw new ApiError(400, "Bad image data."); }
                }
                Icons.setFromBytes(m.dir, data);
                m.cfg.iconAuto = false;
                m.cfg.save();
                out = ok();
            }
            case "POST /api/icon/reset" -> {
                Files.deleteIfExists(Icons.file(m.dir));
                m.cfg.iconAuto = true;
                m.cfg.save();
                if (!Icons.autoFromJar(m.dir)) Icons.generate(m.dir, m.cfg.name);
                out = ok();
            }
            case "GET /api/stats" -> {
                List<Object> l = new ArrayList<>();
                for (double[] s : m.stats()) l.add(List.of((long) s[0], Math.round(s[1] * 10) / 10.0, Math.round(s[2]), (long) s[3]));
                out = l;
            }
            case "GET /api/checks" -> out = m.checks();
            default -> throw new ApiError(404, "Unknown endpoint.");
        }
        json(ex, 200, out);
    }
}
