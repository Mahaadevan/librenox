package mcht;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Whitelist, operators and bans. Uses console commands while running, JSON files while stopped. */
final class Access {
    private Access() {}

    static final Map<String, String> FILE = Map.of("whitelist", "whitelist.json", "ops", "ops.json",
            "banned-players", "banned-players.json", "banned-ips", "banned-ips.json");

    private static String file(String kind) {
        String f = FILE.get(kind);
        if (f == null) throw new ApiError(400, "Unknown list.");
        return f;
    }

    static List<Map<String, Object>> list(Manager m, String kind) throws IOException {
        Path f = m.dir.resolve(file(kind));
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        try {
            for (Object o : Json.list(Json.parse(Files.readString(f, StandardCharsets.UTF_8)))) out.add(Json.obj(o));
        } catch (RuntimeException ignored) {}
        return out;
    }

    private static void write(Path f, List<Map<String, Object>> l) throws IOException {
        Files.writeString(f, Json.stringify(l) + "\n", StandardCharsets.UTF_8);
    }

    private static String checkValue(String kind, String v) {
        v = v == null ? "" : v.strip();
        boolean ip = kind.equals("banned-ips");
        if (ip ? !v.matches("[0-9a-fA-F:.]{3,45}") : !v.matches("[A-Za-z0-9_.]{1,16}")) throw new ApiError(400, ip ? "Enter a valid IP address." : "Enter a valid player name (1-16 letters, digits or _).");
        return v;
    }

    private static String uuid(Manager m, String name) {
        if (!"false".equals(Props.get(m.dir.resolve("server.properties"), "online-mode", "true"))) {
            try {
                String id = Json.str(Json.obj(Net.getJson("https://api.mojang.com/users/profiles/minecraft/" + name)).get("id"));
                if (id.length() == 32) return id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
            } catch (IOException e) {
                throw new ApiError(404, "Could not find a Minecraft account named " + name + " (or Mojang is unreachable).");
            } catch (Exception ignored) {}
            throw new ApiError(404, "Could not find a Minecraft account named " + name + ".");
        }
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void needStable(Manager m) {
        String s = m.state;
        if (s.equals("starting") || s.equals("preparing") || s.equals("stopping")) throw new ApiError(409, "Wait until the server is fully started or stopped.");
    }

    static void add(Manager m, String kind, String value) throws IOException {
        String v = checkValue(kind, value);
        needStable(m);
        if (m.state.equals("online")) {
            String cmd = switch (kind) { case "whitelist" -> "whitelist add "; case "ops" -> "op "; case "banned-players" -> "ban "; default -> "ban-ip "; } + v;
            if (!m.sendRaw(cmd)) throw new ApiError(409, "Server is not running.");
            m.host("> " + cmd);
            return;
        }
        Path f = m.dir.resolve(file(kind));
        List<Map<String, Object>> l = list(m, kind);
        String key = kind.equals("banned-ips") ? "ip" : "name";
        for (Map<String, Object> e : l) if (v.equalsIgnoreCase(Json.str(e.get(key)))) throw new ApiError(409, v + " is already in this list.");
        Map<String, Object> e = new LinkedHashMap<>();
        String now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z"));
        switch (kind) {
            case "whitelist" -> { e.put("uuid", uuid(m, v)); e.put("name", v); }
            case "ops" -> { e.put("uuid", uuid(m, v)); e.put("name", v); e.put("level", 4); e.put("bypassesPlayerLimit", false); }
            case "banned-players" -> { e.put("uuid", uuid(m, v)); e.put("name", v); e.put("created", now); e.put("source", "MC Host"); e.put("expires", "forever"); e.put("reason", "Banned by an operator."); }
            default -> { e.put("ip", v); e.put("created", now); e.put("source", "MC Host"); e.put("expires", "forever"); e.put("reason", "Banned by an operator."); }
        }
        l.add(e);
        write(f, l);
        m.host("Added " + v + " to " + kind + ".");
    }

    static void remove(Manager m, String kind, String value) throws IOException {
        String v = checkValue(kind, value);
        needStable(m);
        if (m.state.equals("online")) {
            String cmd = switch (kind) { case "whitelist" -> "whitelist remove "; case "ops" -> "deop "; case "banned-players" -> "pardon "; default -> "pardon-ip "; } + v;
            if (!m.sendRaw(cmd)) throw new ApiError(409, "Server is not running.");
            m.host("> " + cmd);
            return;
        }
        String key = kind.equals("banned-ips") ? "ip" : "name";
        List<Map<String, Object>> l = list(m, kind);
        l.removeIf(e -> v.equalsIgnoreCase(Json.str(e.get(key))));
        write(m.dir.resolve(file(kind)), l);
        m.host("Removed " + v + " from " + kind + ".");
    }
}
