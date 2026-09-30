package mcht;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-server settings stored in servers/<id>/config.json. */
final class Config {
    static final Set<String> SOFTWARE = Set.of("paper", "folia", "purpur", "vanilla", "fabric", "custom");
    static final Set<String> PRESETS = Set.of("default", "aikar", "none", "custom");

    final Path file;
    volatile String id = "", name = "Server", software = "paper", version = "", memory = "2G", jvmPreset = "default",
            jvmArgs = "", javaPath = "", tunnel = "", tunnelAddress = "", installed = "";
    volatile boolean eula, autotunnel, autoRestart, autostart, iconAuto = true;
    volatile int autoStopMin, backupKeep = 10;
    volatile List<Map<String, Object>> schedules = new ArrayList<>();
    volatile long created = System.currentTimeMillis();

    Config(Path file) { this.file = file; }

    static Config load(Path file) {
        Config c = new Config(file);
        try {
            c.fromMap(Json.obj(Json.parse(Files.readString(file, StandardCharsets.UTF_8))));
        } catch (Exception ignored) {}
        return c;
    }

    synchronized void fromMap(Map<String, Object> m) {
            this.id = Json.str(m.get("id"));
            this.name = Json.str(m.getOrDefault("name", this.name));
            this.software = Json.str(m.getOrDefault("software", this.software));
            this.version = Json.str(m.getOrDefault("version", ""));
            this.memory = Json.str(m.getOrDefault("memory", this.memory));
            this.jvmPreset = Json.str(m.getOrDefault("jvmPreset", this.jvmPreset));
            this.jvmArgs = Json.str(m.get("jvmArgs"));
            this.javaPath = Json.str(m.get("javaPath"));
            this.tunnel = Json.str(m.get("tunnel"));
            this.tunnelAddress = Json.str(m.get("tunnelAddress"));
            this.installed = Json.str(m.get("installed"));
            this.eula = Boolean.TRUE.equals(m.get("eula"));
            this.autotunnel = Boolean.TRUE.equals(m.get("autotunnel"));
            this.autoRestart = Boolean.TRUE.equals(m.get("autoRestart"));
            this.autostart = Boolean.TRUE.equals(m.get("autostart"));
            this.iconAuto = !Boolean.FALSE.equals(m.get("iconAuto"));
            this.autoStopMin = (int) Json.num(m.get("autoStopMin"));
            this.backupKeep = m.get("backupKeep") instanceof Number n ? n.intValue() : 10;
            this.created = m.get("created") instanceof Number n ? n.longValue() : this.created;
            List<Map<String, Object>> s = new ArrayList<>();
            for (Object o : Json.list(m.get("schedules"))) s.add(Json.obj(o));
            this.schedules = s;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("software", software); m.put("version", version);
        m.put("memory", memory); m.put("jvmPreset", jvmPreset); m.put("jvmArgs", jvmArgs); m.put("javaPath", javaPath);
        m.put("eula", eula); m.put("tunnel", tunnel); m.put("tunnelAddress", tunnelAddress); m.put("autotunnel", autotunnel);
        m.put("autoRestart", autoRestart); m.put("autostart", autostart); m.put("autoStopMin", autoStopMin);
        m.put("backupKeep", backupKeep); m.put("schedules", schedules); m.put("installed", installed);
        m.put("iconAuto", iconAuto); m.put("created", created);
        return m;
    }

    synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.stringify(toMap()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private static int intOf(Object v, String label, int min, int max) {
        try {
            int n = v instanceof Number x ? x.intValue() : Integer.parseInt(Json.str(v).trim());
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ApiError(400, label + " must be a number between " + min + " and " + max + ".");
        }
    }

    static String clean(String s, int max) {
        String t = s.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Validate and apply user-editable settings (only keys present in m). */
    synchronized void apply(Map<String, Object> m) {
        Map<String, Object> snap = toMap();
        try { applyInner(m); } catch (ApiError e) { fromMap(snap); throw e; }
    }

    private void applyInner(Map<String, Object> m) {
        if (m.containsKey("name")) {
            String n = clean(Json.str(m.get("name")), 40);
            if (n.isEmpty()) throw new ApiError(400, "Name cannot be empty.");
            name = n;
        }
        if (m.containsKey("software")) {
            String s = Json.str(m.get("software"));
            if (!SOFTWARE.contains(s)) throw new ApiError(400, "Unknown server software.");
            software = s;
        }
        if (m.containsKey("version")) {
            String v = Json.str(m.get("version")).trim();
            if (!v.matches("[0-9A-Za-z._\\-]{0,40}")) throw new ApiError(400, "Invalid version.");
            version = v;
        }
        if (m.containsKey("memory")) {
            String v = Json.str(m.get("memory")).trim();
            if (!v.matches("\\d{1,5}[MmGg]")) throw new ApiError(400, "Memory must look like 2G or 2048M.");
            long mb = Long.parseLong(v.substring(0, v.length() - 1)) * (Character.toUpperCase(v.charAt(v.length() - 1)) == 'G' ? 1024 : 1);
            if (mb < 256) throw new ApiError(400, "Memory must be at least 256M.");
            memory = v.toUpperCase();
        }
        if (m.containsKey("jvmPreset")) {
            String p = Json.str(m.get("jvmPreset"));
            if (!PRESETS.contains(p)) throw new ApiError(400, "Unknown JVM preset.");
            jvmPreset = p;
        }
        if (m.containsKey("jvmArgs")) {
            String a = Json.str(m.get("jvmArgs")).replaceAll("\\s+", " ").trim();
            if (a.length() > 2000) throw new ApiError(400, "JVM arguments are too long.");
            for (String t : a.isEmpty() ? new String[0] : a.split(" "))
                if (!t.startsWith("-") || t.length() > 300 || t.matches(".*\\p{Cntrl}.*")) throw new ApiError(400, "Every JVM argument must start with '-'.");
            jvmArgs = a;
        }
        if (m.containsKey("javaPath")) {
            String p = Json.str(m.get("javaPath")).trim();
            if (!p.isEmpty() && !Files.isRegularFile(Path.of(p))) throw new ApiError(400, "That Java executable does not exist.");
            javaPath = p;
        }
        if (m.containsKey("autoRestart")) autoRestart = Boolean.TRUE.equals(m.get("autoRestart"));
        if (m.containsKey("autostart")) autostart = Boolean.TRUE.equals(m.get("autostart"));
        if (m.containsKey("autoStopMin")) autoStopMin = intOf(m.get("autoStopMin"), "Auto-stop minutes", 0, 1440);
        if (m.containsKey("backupKeep")) backupKeep = intOf(m.get("backupKeep"), "Backups to keep", 1, 1000);
        if (m.containsKey("schedules")) {
            List<Map<String, Object>> out = new ArrayList<>();
            List<Object> in = Json.list(m.get("schedules"));
            if (in.size() > 50) throw new ApiError(400, "Too many schedules.");
            for (Object o : in) {
                Map<String, Object> s = Json.obj(o), r = new LinkedHashMap<>();
                String type = Json.str(s.get("type"));
                if (!Set.of("command", "restart", "backup").contains(type)) throw new ApiError(400, "Unknown schedule type.");
                String cmd = clean(Json.str(s.get("command")), 200);
                if (type.equals("command") && cmd.isEmpty()) throw new ApiError(400, "A command schedule needs a command.");
                r.put("type", type);
                r.put("everyMinutes", intOf(s.get("everyMinutes"), "Schedule interval (minutes)", 1, 10080));
                r.put("command", type.equals("command") ? cmd.replaceFirst("^/", "") : "");
                r.put("enabled", !Boolean.FALSE.equals(s.get("enabled")));
                out.add(r);
            }
            schedules = out;
        }
        save();
    }
}
