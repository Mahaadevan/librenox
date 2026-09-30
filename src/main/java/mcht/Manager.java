package mcht;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** One Minecraft server: lifecycle, logs, add-ons, backups, automation. */
final class Manager {
    interface Job { void run() throws Exception; }

    record Log(long id, String ts, String kind, String lvl, String text) {}

    static final Pattern LOG_RE = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)\\]? ?\\[?(?:[^/\\]\\[]*/)?([A-Z]+)\\]:? ?(.*)$");
    static final Pattern DONE_RE = Pattern.compile("Done \\([\\d.,]+s\\)!");
    static final Pattern JOIN_RE = Pattern.compile("^(\\S+) joined the game$");
    static final Pattern LEAVE_RE = Pattern.compile("^(\\S+) left the game$");
    static final Pattern SAVE_DONE_RE = Pattern.compile("Saved the (world|game)", Pattern.CASE_INSENSITIVE);
    static final String GEYSER = "https://download.geysermc.org/v2/projects/%s/versions/latest/builds/latest/downloads/spigot";
    static final List<String> AIKAR = List.of("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
            "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    final Config cfg;
    final Path home, dir, bak, props;
    final Tunnel tunnel = new Tunnel(this);
    final Set<String> players = ConcurrentHashMap.newKeySet();
    volatile String state = "offline", task = "";
    volatile double progress = -1, cpu, rssMb = -1;
    private volatile String busy = "";
    private volatile long t0, ready, emptySince;
    private volatile boolean stopRequested;
    private volatile Process proc;
    private BufferedWriter procIn;
    private final Object inLock = new Object();
    private final ArrayDeque<Log> logs = new ArrayDeque<>();
    private long seq;
    private final ArrayDeque<double[]> stats = new ArrayDeque<>();
    private long lastCpuNanos, lastCpuWall;
    private final Map<String, Long> nextRun = new HashMap<>();
    private final ArrayDeque<Long> crashes = new ArrayDeque<>();

    Manager(Config cfg, Path home) throws IOException {
        this.cfg = cfg;
        this.home = home;
        this.dir = home.resolve("server");
        this.bak = home.resolve("backups");
        this.props = dir.resolve("server.properties");
        Files.createDirectories(dir);
        Files.createDirectories(bak);
    }

    int port() {
        try { return Integer.parseInt(Props.get(props, "server-port", "25565").trim()); } catch (NumberFormatException e) { return 25565; }
    }

    // ------------------------------------------------------------ logging & tasks
    void log(String kind, String text) {
        String lvl = "";
        if (kind.equals("srv")) {
            Matcher m = LOG_RE.matcher(text);
            if (m.matches()) { lvl = m.group(2); text = m.group(3); }
            else {
                String t = text.stripLeading();
                if (t.startsWith("at ") || t.startsWith("Caused") || t.startsWith("java.") || t.startsWith("Exception")) lvl = "TRACE";
            }
        }
        synchronized (logs) {
            logs.addLast(new Log(++seq, LocalTime.now().format(TS), kind, lvl, text));
            while (logs.size() > 1500) logs.removeFirst();
        }
    }

    void host(String text) { log("host", text); }

    List<Log> logsAfter(long after) {
        synchronized (logs) {
            List<Log> out = new ArrayList<>();
            for (Log l : logs) if (l.id() > after) out.add(l);
            return out;
        }
    }

    long lastLogId() { synchronized (logs) { return seq; } }

    void setTask(String label, double frac) { task = label; progress = frac; }

    void clearTask() { task = ""; progress = -1; }

    void fetch(String url, Path target, String label) throws Exception {
        try { Net.download(url, target, f -> setTask(label, f)); } finally { clearTask(); }
    }

    void work(String label, Job job) {
        synchronized (this) {
            if (!busy.isEmpty()) throw new ApiError(409, "Busy: " + busy + " - please wait.");
            busy = label;
        }
        Thread t = new Thread(() -> {
            try { job.run(); } catch (Exception e) { log("err", label + " failed: " + Util.msg(e)); }
            finally { busy = ""; clearTask(); }
        }, "job-" + cfg.id);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ downloads
    private String ensureJava(int need) throws Exception {
        if (!cfg.javaPath.isEmpty()) {
            int mj = JavaRt.major(cfg.javaPath);
            if (mj == 0) throw new IOException("The custom Java path does not work: " + cfg.javaPath);
            if (mj < need) host("Warning: custom Java is version " + mj + " but Minecraft " + cfg.version + " needs " + need + "+.");
            return cfg.javaPath;
        }
        String found = JavaRt.locate(need);
        if (found != null && JavaRt.major(found) >= need) return found;
        host("Java " + need + "+ not found - downloading a private copy (one time only).");
        String os = Util.IS_WIN ? "windows" : Util.IS_MAC ? "mac" : "linux";
        String arch = Util.isArm() ? "aarch64" : "x64";
        String url = "https://api.adoptium.net/v3/binary/latest/" + need + "/ga/" + os + "/" + arch + "/jre/hotspot/normal/eclipse";
        Path archive = Dirs.TMP.resolve("jre" + need + (Util.IS_WIN ? ".zip" : ".tar.gz"));
        fetch(url, archive, "Downloading Java " + need);
        setTask("Unpacking Java", -1);
        Path dest = Dirs.RUNTIME.resolve("jre-" + need);
        Archive.extract(archive, dest);
        Files.deleteIfExists(archive);
        Path exe = JavaRt.find(dest);
        if (exe == null) throw new IOException("Java download unpacked but no java executable was found.");
        exe.toFile().setExecutable(true);
        return exe.toString();
    }

    private Path prepareJar() throws Exception {
        Path jar = dir.resolve("server.jar");
        String sw = cfg.software;
        boolean have = Files.isRegularFile(jar) && Files.size(jar) > 50_000;
        if (sw.equals("custom")) {
            if (!have) throw new IOException("Upload your server jar as server.jar (Files page) before starting.");
            return jar;
        }
        if (have && !cfg.version.isEmpty() && cfg.installed.startsWith(sw + "|" + cfg.version + "|")) return jar;
        Providers.Build b;
        try {
            setTask("Checking " + sw, -1);
            b = Providers.resolve(sw, cfg.version);
        } catch (Exception e) {
            if (have && cfg.installed.startsWith(sw + "|")) {
                host("Could not check for updates (" + Util.msg(e) + ") - using the existing jar.");
                if (cfg.version.isEmpty()) cfg.version = cfg.installed.split("\\|")[1];
                return jar;
            }
            throw e;
        }
        String key = sw + "|" + b.version() + "|" + b.build();
        if (!(have && key.equals(cfg.installed))) {
            host("Downloading " + sw + " " + b.version() + " (build " + b.build() + ") ...");
            fetch(b.url(), jar, "Downloading " + sw + " " + b.version());
            if (Files.size(jar) < 50_000) { Files.deleteIfExists(jar); throw new IOException("Download was incomplete."); }
            cfg.installed = key;
            if (cfg.iconAuto && Icons.autoFromJar(dir)) host("Server icon taken from the downloaded jar.");
        }
        cfg.version = b.version();
        cfg.save();
        return jar;
    }

    Path fetchTool(String repo, String name) throws Exception {
        Path exe = Dirs.BIN.resolve(name + (Util.IS_WIN ? ".exe" : ""));
        if (Files.exists(exe)) return exe;
        Map<String, Object> asset = GitHub.releaseAsset(repo, name);
        host("Downloading " + name + " ...");
        Path dl = Util.safeChild(Dirs.TMP, Json.str(asset.get("name")));
        fetch(Json.str(asset.get("browser_download_url")), dl, "Downloading " + name);
        Files.createDirectories(Dirs.BIN);
        String fn = dl.getFileName().toString();
        if (fn.endsWith(".zip") || fn.endsWith(".tar.gz") || fn.endsWith(".tgz")) {
            Path out = Dirs.TMP.resolve(name + "-x");
            Archive.extract(dl, out);
            Path found;
            try (Stream<Path> w = Files.walk(out)) {
                found = w.filter(p -> Files.isRegularFile(p) && (p.getFileName().toString().equalsIgnoreCase(name)
                        || p.getFileName().toString().equalsIgnoreCase(name + ".exe"))).findFirst().orElse(null);
            }
            if (found == null) throw new IOException(name + " archive did not contain the program.");
            Files.copy(found, exe, StandardCopyOption.REPLACE_EXISTING);
            Util.deleteTree(out);
        } else Files.copy(dl, exe, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(dl);
        exe.toFile().setExecutable(true);
        return exe;
    }

    // ------------------------------------------------------------ lifecycle
    private List<String> jvmFlags() {
        return switch (cfg.jvmPreset) {
            case "aikar" -> AIKAR;
            case "none" -> List.of();
            case "custom" -> cfg.jvmArgs.isEmpty() ? List.of() : Arrays.asList(cfg.jvmArgs.split(" "));
            default -> List.of("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled");
        };
    }

    private void boot() throws Exception {
        state = "preparing";
        stopRequested = false;
        try {
            int port = port();
            if (Util.portOpen(port)) {
                boolean freed = false;
                for (int i = 0; i < 10 && !freed; i++) { Thread.sleep(200); freed = !Util.portOpen(port); }
                if (!freed) throw new IOException("Port " + port + " is already in use (another server running?). Change it in Options.");
            }
            Path jar = prepareJar();
            String java = ensureJava(JavaRt.required(cfg.version));
            Files.writeString(dir.resolve("eula.txt"), "# accepted in Minecraft Server Hosting Tool\neula=true\n", StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of(java, "-Xms" + cfg.memory, "-Xmx" + cfg.memory));
            cmd.addAll(jvmFlags());
            cmd.addAll(List.of("-Dfile.encoding=UTF-8", "-jar", jar.getFileName().toString(), "--nogui"));
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            synchronized (inLock) {
                proc = p;
                procIn = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            }
            state = "starting";
            t0 = System.currentTimeMillis();
            ready = 0;
            players.clear();
            host("Launching " + cfg.software + " " + cfg.version + " with " + cfg.memory + " RAM ...");
            Thread t = new Thread(() -> pump(p), "server-pump-" + cfg.id);
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            state = "offline";
            throw e;
        }
    }

    private void pump(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) ingest(line);
        } catch (IOException ignored) {}
        int code;
        try { code = p.waitFor(); } catch (InterruptedException e) { code = -1; }
        synchronized (inLock) {
            if (proc != p) return;
            proc = null;
            procIn = null;
            state = "offline";
            players.clear();
            host("Server stopped (exit code " + code + ").");
        }
        if (code != 0 && !stopRequested && cfg.autoRestart) {
            long now = System.currentTimeMillis();
            crashes.addLast(now);
            while (!crashes.isEmpty() && now - crashes.peekFirst() > 600_000) crashes.removeFirst();
            if (crashes.size() <= 3) {
                host("Server crashed - restarting in 5 seconds (auto-restart is on).");
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                        if (state.equals("offline")) { state = "preparing"; work("Restarting after crash", this::boot); }
                    } catch (Exception e) { state = "offline"; }
                }, "crash-restart");
                t.setDaemon(true);
                t.start();
            } else log("err", "Server crashed 3 times in 10 minutes - auto-restart paused.");
        }
    }

    private void ingest(String line) {
        line = Util.ANSI.matcher(line).replaceAll("").replace("\t", "    ");
        if (line.isBlank()) return;
        log("srv", line);
        Matcher m = LOG_RE.matcher(line);
        String msg = m.matches() ? m.group(3) : line;
        Matcher j = JOIN_RE.matcher(msg), l = LEAVE_RE.matcher(msg);
        if (DONE_RE.matcher(msg).find() && state.equals("starting")) {
            state = "online";
            ready = System.currentTimeMillis();
            host("Server is ONLINE - LAN players join: " + Util.lanIp() + ":" + port());
            if (cfg.autotunnel && !cfg.tunnel.isEmpty() && !tunnel.alive()) tunnelAsync(cfg.tunnel);
        } else if (j.matches()) players.add(j.group(1));
        else if (l.matches()) players.remove(l.group(1));
        else if (line.contains("UnsupportedClassVersionError")) log("err", "Java too old for this version - delete minecraft-host/runtime or set a newer Java in Startup.");
        else if (line.toUpperCase().contains("FAILED TO BIND")) log("err", "Port is taken - change it in Options.");
    }

    void stopServer() {
        Process p = proc;
        if (p == null || !p.isAlive()) return;
        stopRequested = true;
        state = "stopping";
        sendRaw("stop");
        try {
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                host("Server did not stop in time - killing it.");
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            for (int i = 0; i < 30 && !state.equals("offline"); i++) Thread.sleep(100);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    boolean sendRaw(String cmd) {
        synchronized (inLock) {
            Process p = proc;
            if (p == null || !p.isAlive() || procIn == null) return false;
            try { procIn.write(cmd + "\n"); procIn.flush(); return true; } catch (IOException e) { return false; }
        }
    }

    boolean running() { return state.equals("starting") || state.equals("online"); }

    void start() {
        if (!cfg.eula) throw new ApiError(400, "Accept the Minecraft EULA first.");
        if (!state.equals("offline")) throw new ApiError(409, "Server is " + state + ".");
        state = "preparing";
        try { work("Preparing server", this::boot); } catch (ApiError e) { state = "offline"; throw e; }
    }

    void stop() {
        if (!running()) throw new ApiError(409, "Server is not running.");
        work("Stopping server", this::stopServer);
    }

    void restart() {
        if (!running()) throw new ApiError(409, "Server is not running.");
        work("Restarting server", () -> { stopServer(); boot(); });
    }

    void command(String cmd) {
        cmd = cmd == null ? "" : cmd.replace("\r", "").replace("\n", "").strip();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty()) throw new ApiError(400, "Empty command.");
        if (!sendRaw(cmd)) throw new ApiError(409, "Server is not running.");
        host("> " + cmd);
    }

    void acceptEula() { cfg.eula = true; cfg.save(); }

    // ------------------------------------------------------------ tunnel
    void tunnelAsync(String provider) {
        Thread t = new Thread(() -> {
            try { tunnel.start(provider); } catch (Exception e) { tunnel.status = "failed"; log("err", "Tunnel: " + Util.msg(e)); }
            finally { clearTask(); }
        }, "tunnel-start");
        t.setDaemon(true);
        t.start();
    }

    void tunnelStart(String provider) {
        if (!Set.of("playit", "bore", "ngrok").contains(provider)) throw new ApiError(400, "Unknown tunnel provider.");
        cfg.tunnel = provider;
        cfg.autotunnel = true;
        cfg.save();
        host("Starting " + provider + " tunnel ...");
        tunnelAsync(provider);
    }

    void tunnelStop() {
        cfg.autotunnel = false;
        cfg.save();
        Thread t = new Thread(tunnel::stop, "tunnel-stop");
        t.setDaemon(true);
        t.start();
        host("Tunnel stopped.");
    }

    void setCustomAddress(String addr) {
        String a = addr == null ? "" : addr.strip();
        if (a.length() > 255 || !a.matches("[A-Za-z0-9.:\\-\\[\\]]*")) throw new ApiError(400, "That does not look like an address.");
        cfg.tunnelAddress = a;
        cfg.save();
    }

    // ------------------------------------------------------------ add-ons
    private void checkType(String t) { if (!Set.of("plugin", "mod", "datapack").contains(t)) throw new ApiError(400, "Unknown type."); }

    private String ext(String t) { return t.equals("datapack") ? ".zip" : ".jar"; }

    private Path extDir(String t) {
        checkType(t);
        if (t.equals("plugin")) return dir.resolve("plugins");
        if (t.equals("mod")) return dir.resolve("mods");
        return FileApi.res(dir, Props.get(props, "level-name", "world") + "/datapacks");
    }

    private List<String> loaders(String t) {
        return switch (t) {
            case "plugin" -> switch (cfg.software) { case "folia" -> List.of("folia", "paper", "spigot", "bukkit"); case "purpur" -> List.of("purpur", "paper", "spigot", "bukkit"); default -> List.of("paper", "spigot", "bukkit"); };
            case "mod" -> cfg.software.equals("fabric") ? List.of("fabric") : List.of("fabric", "forge", "neoforge", "quilt");
            default -> List.of("datapack");
        };
    }

    List<Map<String, Object>> search(String type, String q) throws Exception {
        checkType(type);
        if (q == null || q.isBlank()) throw new ApiError(400, "Type something to search for.");
        return Modrinth.search(q.strip(), type, cfg.version, loaders(type));
    }

    void install(String type, String projectId, String title) {
        checkType(type);
        if (!projectId.matches("[A-Za-z0-9_\\-]{1,64}")) throw new ApiError(400, "Bad project id.");
        String label = title == null || title.isBlank() ? projectId : title.replaceAll("\\p{Cntrl}", "");
        work("Installing " + label, () -> {
            Modrinth.Jar f = Modrinth.newest(projectId, loaders(type), cfg.version, ext(type));
            Files.createDirectories(extDir(type));
            fetch(f.url(), Util.safeChild(extDir(type), f.filename()), "Downloading " + label);
            host("Installed " + label + " (" + f.filename() + "). Restart to load it.");
        });
    }

    List<Map<String, Object>> installed(String type) throws IOException {
        Path d = extDir(type);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(d)) return out;
        String e = ext(type);
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().endsWith(e) || x.getFileName().toString().endsWith(e + ".disabled")).sorted()::iterator) {
                String n = p.getFileName().toString();
                out.add(Map.of("name", n, "size", Files.size(p), "enabled", !n.endsWith(".disabled")));
            }
        }
        return out;
    }

    private Path extFile(String type, String name) {
        String e = ext(type);
        if (!name.endsWith(e) && !name.endsWith(e + ".disabled")) throw new ApiError(400, "Not an add-on file.");
        return Util.safeChild(extDir(type), name);
    }

    void remove(String type, String name) throws IOException {
        Files.deleteIfExists(extFile(type, name));
        host("Removed " + name + ". Restart to apply.");
    }

    void toggle(String type, String name) throws IOException {
        Path p = extFile(type, name);
        String n = p.getFileName().toString();
        Path t = p.resolveSibling(n.endsWith(".disabled") ? n.substring(0, n.length() - 9) : n + ".disabled");
        Files.move(p, t);
        host((n.endsWith(".disabled") ? "Enabled " : "Disabled ") + t.getFileName().toString().replace(".disabled", "") + ". Restart to apply.");
    }

    void geyser() {
        work("Installing Bedrock crossplay", () -> {
            Files.createDirectories(dir.resolve("plugins"));
            for (String n : List.of("geyser", "floodgate")) {
                host("Downloading " + n + " ...");
                fetch(String.format(GEYSER, n), dir.resolve("plugins").resolve(n + "-spigot.jar"), "Downloading " + n);
            }
            host("Geyser + Floodgate installed. Restart. Bedrock players connect on UDP 19132.");
        });
    }

    // ------------------------------------------------------------ software
    void changeSoftware(String sw, String version, boolean backup) {
        if (!state.equals("offline")) throw new ApiError(409, "Stop the server before changing its software.");
        if (!Config.SOFTWARE.contains(sw)) throw new ApiError(400, "Unknown server software.");
        if (!version.matches("[0-9A-Za-z._\\-]{0,40}")) throw new ApiError(400, "Invalid version.");
        work("Switching software", () -> {
            if (!sw.equals("custom")) Providers.resolve(sw, version); // validates that it exists
            if (backup) doBackup("pre-switch-");
            cfg.software = sw;
            cfg.version = version;
            cfg.installed = "";
            cfg.save();
            host("Software set to " + sw + " " + (version.isEmpty() ? "(newest)" : version) + ". It downloads on next start.");
        });
    }

    void updateBuild() {
        if (!state.equals("offline")) throw new ApiError(409, "Stop the server first.");
        cfg.installed = "";
        cfg.save();
        host("Will download the newest build of " + cfg.software + " " + cfg.version + " on next start.");
    }

    // ------------------------------------------------------------ backups
    Path doBackup(String prefix) throws Exception {
        boolean live = state.equals("online");
        try {
            if (live) {
                long mark = lastLogId();
                sendRaw("save-off");
                sendRaw("save-all flush");
                long deadline = System.currentTimeMillis() + 20_000;
                boolean saved = false;
                while (System.currentTimeMillis() < deadline && !saved) {
                    saved = logsAfter(mark).stream().anyMatch(l -> l.kind().equals("srv") && SAVE_DONE_RE.matcher(l.text()).find());
                    if (!saved) Thread.sleep(250);
                }
                if (!saved) Thread.sleep(2000);
            }
            Files.createDirectories(bak);
            Path target = bak.resolve(prefix + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
            Set<String> skip = Set.of("cache", "logs", "libraries", "versions", "plugins", "mods");
            setTask("Creating backup", -1);
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(target)); Stream<Path> w = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) w::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    Path rel = dir.relativize(p);
                    String fn = p.getFileName().toString();
                    if (skip.contains(rel.getName(0).toString()) || fn.equals("server.jar") || fn.equals("session.lock")) continue;
                    try {
                        z.putNextEntry(new ZipEntry(rel.toString().replace('\\', '/')));
                        Files.copy(p, z);
                        z.closeEntry();
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
            long size = Files.size(target);
            host("Backup saved: " + target.getFileName() + " (" + (size >= 1 << 20 ? size / 1024 / 1024 + " MB" : Math.max(1, size / 1024) + " KB") + ")");
            if (prefix.equals("auto-")) prune();
            return target;
        } finally {
            if (live) sendRaw("save-on");
        }
    }

    private void prune() throws IOException {
        List<Path> autos;
        try (Stream<Path> s = Files.list(bak)) {
            autos = s.filter(p -> p.getFileName().toString().startsWith("auto-")).sorted(Comparator.reverseOrder()).toList();
        }
        for (int i = cfg.backupKeep; i < autos.size(); i++) Files.deleteIfExists(autos.get(i));
    }

    void backup() { work("Backing up", () -> doBackup("world-")); }

    void backupAuto() { work("Backing up", () -> doBackup("auto-")); }

    void restore(String name) {
        Path zip = backupFile(name);
        if (!state.equals("offline")) throw new ApiError(409, "Stop the server before restoring a backup.");
        work("Restoring backup", () -> {
            doBackup("pre-restore-");
            Set<String> tops = new HashSet<>();
            try (ZipFile z = new ZipFile(zip.toFile())) {
                for (ZipEntry e : Collections.list(z.entries())) if (e.getName().contains("/")) tops.add(e.getName().substring(0, e.getName().indexOf('/')));
            }
            for (String t : tops) {
                Path p = dir.resolve(t).normalize();
                if (p.startsWith(dir) && !p.equals(dir)) Util.deleteTree(p);
            }
            setTask("Restoring " + name, -1);
            Archive.unzip(zip, dir);
            host("Restored " + name + ". A safety backup was made first (pre-restore-*).");
        });
    }

    List<Map<String, Object>> backups() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(bak)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().endsWith(".zip")).sorted(Comparator.reverseOrder())::iterator) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getFileName().toString());
                m.put("size", Files.size(p));
                m.put("modified", Files.getLastModifiedTime(p).toMillis());
                out.add(m);
            }
        }
        return out;
    }

    Path backupFile(String name) {
        Path p = Util.safeChild(bak, name);
        if (!p.getFileName().toString().endsWith(".zip") || !Files.isRegularFile(p)) throw new ApiError(404, "Backup not found.");
        return p;
    }

    // ------------------------------------------------------------ automation & stats
    void tick() {
        sample();
        long now = System.currentTimeMillis();
        if (state.equals("online") && players.isEmpty()) {
            if (emptySince == 0) emptySince = now;
            else if (cfg.autoStopMin > 0 && now - emptySince >= cfg.autoStopMin * 60_000L) {
                emptySince = 0;
                host("No players for " + cfg.autoStopMin + " min - stopping the server (auto-stop).");
                try { stop(); } catch (ApiError ignored) {}
            }
        } else emptySince = 0;
        for (Map<String, Object> s : cfg.schedules) {
            if (!Boolean.TRUE.equals(s.get("enabled"))) continue;
            String type = Json.str(s.get("type")), cmd = Json.str(s.get("command"));
            long every = Json.num(s.get("everyMinutes")) * 60_000L;
            String key = type + "|" + cmd + "|" + every;
            Long due = nextRun.get(key);
            if (due == null) { nextRun.put(key, now + every); continue; }
            if (now < due) continue;
            nextRun.put(key, now + every);
            try {
                switch (type) {
                    case "command" -> { if (state.equals("online")) command(cmd); }
                    case "restart" -> {
                        if (state.equals("online")) work("Scheduled restart", () -> {
                            sendRaw("say Server restarting in 30 seconds");
                            Thread.sleep(30_000);
                            stopServer();
                            boot();
                        });
                    }
                    default -> backupAuto();
                }
            } catch (ApiError e) { nextRun.put(key, now + 30_000); }
        }
    }

    private void sample() {
        Process p = proc;
        double c = 0, r = -1;
        if (p != null && p.isAlive()) {
            ProcessHandle h = p.toHandle();
            long cn = h.info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L), now = System.nanoTime();
            if (cn >= 0 && lastCpuWall > 0) c = Math.max(0, Math.min(100, 100.0 * (cn - lastCpuNanos) / (now - lastCpuWall) / Runtime.getRuntime().availableProcessors()));
            lastCpuNanos = cn;
            lastCpuWall = now;
            r = rss(h.pid());
        } else lastCpuWall = 0;
        cpu = c;
        rssMb = r;
        synchronized (stats) {
            stats.addLast(new double[]{System.currentTimeMillis(), c, r, players.size()});
            while (stats.size() > 180) stats.removeFirst();
        }
    }

    private static double rss(long pid) {
        if (Util.IS_WIN) return -1;
        try {
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).redirectErrorStream(true).start();
            String o = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ps.waitFor(2, TimeUnit.SECONDS);
            return Long.parseLong(o) / 1024.0;
        } catch (Exception e) { return -1; }
    }

    List<double[]> stats() { synchronized (stats) { return new ArrayList<>(stats); } }

    // ------------------------------------------------------------ info
    private long jarCount(String d) {
        Path p = dir.resolve(d);
        if (!Files.isDirectory(p)) return 0;
        try (Stream<Path> s = Files.list(p)) { return s.filter(x -> x.getFileName().toString().endsWith(".jar")).count(); } catch (IOException e) { return 0; }
    }

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, String> pr = Props.read(props);
        String st = state;
        long now = System.currentTimeMillis();
        m.put("id", cfg.id);
        m.put("name", cfg.name);
        m.put("software", cfg.software);
        m.put("version", cfg.version);
        m.put("memory", cfg.memory);
        m.put("state", st);
        m.put("uptime", st.equals("online") ? (now - ready) / 1000 : st.equals("starting") ? (now - t0) / 1000 : 0);
        m.put("port", port());
        m.put("max_players", pr.getOrDefault("max-players", "20"));
        m.put("motd", pr.getOrDefault("motd", "A Minecraft Server"));
        m.put("online_mode", !"false".equals(pr.get("online-mode")));
        m.put("whitelist", "true".equals(pr.get("white-list")));
        m.put("eula", cfg.eula);
        m.put("players", new ArrayList<>(new TreeSet<>(players)));
        m.put("plugins", jarCount("plugins"));
        m.put("mods", jarCount("mods"));
        m.put("busy", busy);
        m.put("task", task);
        m.put("progress", progress);
        m.put("lan", Util.lanIp());
        m.put("cpu", Math.round(cpu * 10) / 10.0);
        m.put("rss", Math.round(rssMb));
        m.put("icon", Icons.file(dir).toFile().lastModified());
        m.put("installed", cfg.installed);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("provider", tunnel.provider.isEmpty() ? cfg.tunnel : tunnel.provider);
        t.put("status", tunnel.status);
        t.put("address", tunnel.address);
        t.put("claim", tunnel.claim);
        m.put("tunnel", t);
        m.put("custom_address", cfg.tunnelAddress);
        m.put("public", !tunnel.address.isEmpty() ? tunnel.address : cfg.tunnelAddress);
        return m;
    }

    List<Map<String, Object>> checks() {
        String java = cfg.javaPath.isEmpty() ? JavaRt.locate(0) : cfg.javaPath;
        List<Map<String, Object>> out = new ArrayList<>();
        check(out, "Server folder", Files.exists(dir), dir.toString());
        check(out, "Server jar", Files.isRegularFile(dir.resolve("server.jar")), cfg.software.equals("custom") ? "upload server.jar in Files" : "downloaded on first start");
        check(out, "Java runtime", java != null, java != null ? java : "Install Java or let the tool download it");
        check(out, "Port free", !Util.portOpen(port()) || !state.equals("offline"), "TCP " + port());
        check(out, "EULA accepted", cfg.eula, "accept it on first start");
        return out;
    }

    private static void check(List<Map<String, Object>> out, String label, boolean ok, String detail) {
        out.add(Map.of("label", label, "ok", ok, "detail", detail));
    }

    void shutdown() {
        tunnel.stop();
        stopServer();
    }
}
