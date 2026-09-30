package mcht;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** All servers, creation/deletion, legacy migration and the scheduler thread. */
final class Registry {
    private final Map<String, Manager> map = new LinkedHashMap<>();
    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "scheduler");
        t.setDaemon(true);
        return t;
    });

    Registry() throws IOException {
        migrate();
        try (Stream<Path> s = Files.list(Dirs.SERVERS)) {
            for (Path d : (Iterable<Path>) s.filter(p -> Files.isRegularFile(p.resolve("config.json"))).sorted()::iterator) {
                Config c = Config.load(d.resolve("config.json"));
                c.id = d.getFileName().toString();
                Manager m = new Manager(c, d);
                if (!Files.exists(Icons.file(m.dir))) Icons.generate(m.dir, c.name);
                map.put(c.id, m);
            }
        }
        ses.scheduleWithFixedDelay(() -> {
            for (Manager m : all()) try { m.tick(); } catch (Throwable ignored) {}
        }, 5, 10, TimeUnit.SECONDS);
    }

    /** Servers flagged "start with launcher". */
    void autostart() {
        for (Manager m : all()) if (m.cfg.autostart && m.cfg.eula) try { m.start(); } catch (ApiError ignored) {}
    }

    /** Move the old single-server layout (minecraft-host/server + host.json) into servers/main. */
    private void migrate() throws IOException {
        Path oldServer = Dirs.BASE.resolve("server"), oldCfg = Dirs.BASE.resolve("host.json");
        if (!Files.isDirectory(oldServer) || Files.exists(Dirs.SERVERS.resolve("main"))) return;
        Path home = Dirs.SERVERS.resolve("main");
        Files.createDirectories(home);
        Files.move(oldServer, home.resolve("server"));
        if (Files.isDirectory(Dirs.BASE.resolve("backups"))) Files.move(Dirs.BASE.resolve("backups"), home.resolve("backups"));
        Config c = new Config(home.resolve("config.json"));
        c.id = "main";
        c.name = "My Server";
        Map<String, String> pr = new LinkedHashMap<>();
        if (Files.exists(oldCfg)) {
            Map<String, Object> o = Json.obj(Json.parse(Files.readString(oldCfg, StandardCharsets.UTF_8)));
            c.version = Json.str(o.get("version"));
            c.memory = Json.str(o.getOrDefault("memory", "2G"));
            c.eula = Boolean.TRUE.equals(o.get("eula"));
            c.tunnel = Json.str(o.get("tunnel"));
            c.tunnelAddress = Json.str(o.get("tunnel_address"));
            c.autotunnel = Boolean.TRUE.equals(o.get("autotunnel"));
            if (o.get("port") != null) pr.put("server-port", Json.str(o.get("port")));
            if (o.get("max_players") != null) pr.put("max-players", Json.str(o.get("max_players")));
            if (o.get("online_mode") != null) pr.put("online-mode", Json.str(o.get("online_mode")));
            if (o.get("motd") != null) pr.put("motd", Json.str(o.get("motd")));
            Files.move(oldCfg, Dirs.BASE.resolve("host.json.migrated"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path server = home.resolve("server");
        try (Stream<Path> s = Files.list(server)) {
            Path jar = s.filter(p -> p.getFileName().toString().matches("paper-.*\\.jar")).findFirst().orElse(null);
            if (jar != null) { Files.move(jar, server.resolve("server.jar")); c.installed = "paper|" + c.version + "|migrated"; }
        }
        if (!pr.isEmpty()) Props.update(server.resolve("server.properties"), pr);
        c.save();
    }

    synchronized Manager get(String id) {
        Manager m = id == null ? null : map.get(id);
        if (m == null) throw new ApiError(404, "Unknown server.");
        return m;
    }

    synchronized List<Manager> all() { return new ArrayList<>(map.values()); }

    private int nextPort() {
        int p = 25565;
        Set<Integer> used = new HashSet<>();
        for (Manager m : map.values()) used.add(m.port());
        while (used.contains(p) || Util.portOpen(p)) p++;
        return p;
    }

    synchronized Manager create(Map<String, Object> spec) throws IOException {
        String name = Config.clean(Json.str(spec.get("name")), 40);
        if (name.isEmpty()) throw new ApiError(400, "Give the server a name.");
        if (map.size() >= 50) throw new ApiError(400, "Too many servers.");
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "server";
        if (base.length() > 32) base = base.substring(0, 32);
        String id = base;
        for (int i = 2; map.containsKey(id) || Files.exists(Dirs.SERVERS.resolve(id)); i++) id = base + "-" + i;
        Path home = Dirs.SERVERS.resolve(id);
        Config c = new Config(home.resolve("config.json"));
        c.id = id;
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("name", name);
        apply.put("software", Json.str(spec.getOrDefault("software", "paper")));
        apply.put("version", Json.str(spec.get("version")));
        apply.put("memory", Json.str(spec.getOrDefault("memory", "2G")));
        Files.createDirectories(home);
        try {
            c.apply(apply);
        } catch (ApiError e) {
            Util.deleteTree(home);
            throw e;
        }
        c.eula = Boolean.TRUE.equals(spec.get("eula"));
        c.save();
        Manager m = new Manager(c, home);
        Map<String, String> pr = new LinkedHashMap<>();
        pr.put("server-port", String.valueOf(nextPort()));
        pr.put("motd", name);
        Props.update(m.props, pr);
        String icon = Json.str(spec.get("icon"));
        boolean set = false;
        if (!icon.isEmpty()) {
            try {
                Icons.setFromBytes(m.dir, Base64.getMimeDecoder().decode(icon.substring(icon.indexOf(',') + 1)));
                c.iconAuto = false;
                c.save();
                set = true;
            } catch (RuntimeException ignored) {}
        }
        if (!set) Icons.generate(m.dir, name);
        map.put(id, m);
        return m;
    }

    synchronized void delete(String id, String confirmName) throws IOException {
        Manager m = get(id);
        if (!m.cfg.name.equals(confirmName)) throw new ApiError(400, "Type the server name exactly to confirm.");
        if (!m.state.equals("offline")) throw new ApiError(409, "Stop the server first.");
        m.tunnel.stop();
        Util.deleteTree(m.home);
        map.remove(id);
    }

    void shutdown() {
        List<Thread> ts = new ArrayList<>();
        for (Manager m : all()) {
            Thread t = new Thread(m::shutdown, "shutdown-" + m.cfg.id);
            t.start();
            ts.add(t);
        }
        for (Thread t : ts) try { t.join(100_000); } catch (InterruptedException ignored) {}
    }
}
