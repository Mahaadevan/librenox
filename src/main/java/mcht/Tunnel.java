package mcht;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** playit.gg / bore.pub / ngrok tunnel helper process. */
final class Tunnel {
    private static final Pattern PLAYIT_ADDR = Pattern.compile(
            "\\b((?:[a-z0-9-]+\\.)+(?:joinmc\\.link|ply\\.gg|playit\\.gg)(?::\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIM_RE = Pattern.compile("https://playit\\.gg/claim/\\S+");

    private final Manager app;
    private volatile Process proc;
    private volatile boolean starting;
    volatile String provider = "", status = "off", address = "", claim = "";

    Tunnel(Manager app) { this.app = app; }

    boolean alive() {
        Process p = proc;
        return p != null && p.isAlive();
    }

    void start(String prov) throws Exception {
        if (starting) return;
        starting = true;
        try {
            stop();
            provider = prov;
            address = "";
            claim = "";
            status = "starting";
            String port = String.valueOf(app.port());
            List<List<String>> attempts;
            switch (prov) {
                case "playit" -> {
                    String exe = app.fetchTool("playit-cloud/playit-agent", "playit").toString();
                    attempts = List.of(List.of(exe, "--stdout"), List.of(exe));
                }
                case "bore" -> {
                    String exe = app.fetchTool("ekzhang/bore", "bore").toString();
                    attempts = List.of(List.of(exe, "local", port, "--to", "bore.pub"));
                }
                case "ngrok" -> {
                    String exe = which("ngrok");
                    if (exe == null) throw new IOException("ngrok is not installed (install it and run 'ngrok config add-authtoken ...').");
                    attempts = List.of(List.of(exe, "tcp", port, "--log=stdout"));
                }
                default -> throw new IOException("Unknown tunnel provider " + prov);
            }
            boolean ok = false;
            for (int i = 0; i < attempts.size() && !ok; i++) {
                ProcessBuilder pb = new ProcessBuilder(attempts.get(i)).redirectErrorStream(true)
                        .redirectInput(ProcessBuilder.Redirect.from(new File(Util.IS_WIN ? "NUL" : "/dev/null")));
                String token = Util.env("MCSHT_NGROK_AUTHTOKEN");
                if (prov.equals("ngrok") && !token.isEmpty()) pb.environment().put("NGROK_AUTHTOKEN", token);
                Process p = pb.start();
                proc = p;
                Thread t = new Thread(() -> pump(p), "tunnel-pump");
                t.setDaemon(true);
                t.start();
                Thread.sleep(prov.equals("playit") && i == 0 && attempts.size() > 1 ? 4000 : 1500);
                ok = alive();
            }
            if (!ok) {
                status = "failed";
                throw new IOException("tunnel program exited immediately - see log lines above");
            }
            if (prov.equals("ngrok")) {
                Thread t = new Thread(this::pollNgrok, "ngrok-poll");
                t.setDaemon(true);
                t.start();
            }
            if (prov.equals("playit")) {
                app.log("tun", "If a claim link appears, open it in a browser and approve this agent.");
            }
        } finally {
            starting = false;
        }
    }

    private static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            for (String n : Util.IS_WIN ? new String[]{name + ".exe", name + ".cmd"} : new String[]{name}) {
                Path p = Path.of(dir, n);
                if (java.nio.file.Files.isExecutable(p)) return p.toString();
            }
        }
        return null;
    }

    private void pump(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = r.readLine()) != null) {
                String line = Util.ANSI.matcher(raw).replaceAll("").strip();
                if (line.isEmpty()) continue;
                app.log("tun", line);
                if (provider.equals("playit")) {
                    Matcher c = CLAIM_RE.matcher(line);
                    if (c.find()) { claim = c.group(); status = "claim"; continue; }
                    if (!line.toLowerCase().contains("claim")) {
                        Matcher m = PLAYIT_ADDR.matcher(line);
                        if (m.find() && !m.group(1).toLowerCase().startsWith("api.") && !m.group(1).toLowerCase().startsWith("www.")) {
                            address = m.group(1); status = "online"; claim = "";
                            continue;
                        }
                    }
                    if (status.equals("claim") && Pattern.compile("(?i)tunnel|connected|running").matcher(line).find()) status = "running";
                } else if (provider.equals("bore")) {
                    Matcher m = Pattern.compile("listening at (\\S+)").matcher(line);
                    if (m.find()) { address = m.group(1); status = "online"; }
                }
            }
        } catch (IOException ignored) {}
        if (proc == p) {
            status = status.equals("off") || status.equals("stopping") ? "off" : "failed";
            app.log("tun", "tunnel process ended");
        }
    }

    private void pollNgrok() {
        for (int i = 0; i < 60; i++) {
            if (!alive()) return;
            try {
                for (Object t : Json.list(Json.obj(Net.getJson("http://127.0.0.1:4040/api/tunnels")).get("tunnels"))) {
                    String url = Json.str(Json.obj(t).get("public_url"));
                    if (url.startsWith("tcp://")) { address = url.substring(6); status = "online"; return; }
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    synchronized void stop() {
        Process p = proc;
        if (p != null && p.isAlive()) {
            status = "stopping";
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                p.destroyForcibly();
            }
        }
        proc = null;
        status = "off";
        address = "";
        claim = "";
    }
}
