package mcht;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Download sources for each server software. */
final class Providers {
    private Providers() {}

    record Build(String software, String version, String build, String url) {}

    static final String FILL = "https://fill.papermc.io/v3/projects/";
    static final String PURPUR = "https://api.purpurmc.org/v2/purpur";
    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String FABRIC = "https://meta.fabricmc.net/v2/versions/";

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static int cmp(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0, q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(q, p);
        }
        return 0;
    }

    /** Version list, newest first. */
    static List<String> versions(String sw) throws Exception {
        List<String> out = new ArrayList<>();
        switch (sw) {
            case "paper", "folia" -> {
                Map<String, Object> project = Json.obj(Net.getJson(FILL + sw));
                for (Object group : Json.obj(project.get("versions")).values())
                    for (Object v : Json.list(group)) if (v instanceof String s && s.matches("\\d+(\\.\\d+)+")) out.add(s);
                out.sort(Providers::cmp);
            }
            case "purpur" -> {
                for (Object v : Json.list(Json.obj(Net.getJson(PURPUR)).get("versions"))) out.add(Json.str(v));
                out.sort(Providers::cmp);
            }
            case "vanilla" -> {
                for (Object o : Json.list(Json.obj(Net.getJson(MANIFEST)).get("versions"))) {
                    Map<String, Object> v = Json.obj(o);
                    if ("release".equals(v.get("type"))) out.add(Json.str(v.get("id")));
                }
            }
            case "fabric" -> {
                for (Object o : Json.list(Net.getJson(FABRIC + "game"))) {
                    Map<String, Object> v = Json.obj(o);
                    if (Boolean.TRUE.equals(v.get("stable"))) out.add(Json.str(v.get("version")));
                }
            }
            default -> throw new IOException("This software has no download source.");
        }
        return out;
    }

    static Build resolve(String sw, String version) throws Exception {
        List<String> vs = versions(sw);
        if (!version.isEmpty() && !vs.contains(version))
            throw new IOException(sw + " has no version " + version + ". Newest: " + String.join(", ", vs.subList(0, Math.min(6, vs.size()))));
        for (String c : version.isEmpty() ? vs.subList(0, Math.min(8, vs.size())) : List.of(version)) {
            try {
                Build b = one(sw, c);
                if (b != null) return b;
            } catch (IOException e) {
                if (!version.isEmpty()) throw e;
            }
        }
        throw new IOException("No " + sw + " build is currently available.");
    }

    private static Build one(String sw, String v) throws Exception {
        switch (sw) {
            case "paper", "folia" -> {
                List<Object> builds = Json.list(Net.getJson(FILL + sw + "/versions/" + enc(v) + "/builds"));
                builds.sort((a, b) -> Long.compare(Json.num(Json.obj(b).get("id")), Json.num(Json.obj(a).get("id"))));
                Build fb = null;
                for (Object o : builds) {
                    Map<String, Object> b = Json.obj(o);
                    String url = Json.str(Json.obj(Json.obj(b.get("downloads")).get("server:default")).get("url"));
                    if (url.isEmpty()) continue;
                    Build cand = new Build(sw, v, String.valueOf(Json.num(b.get("id"))), url);
                    if ("STABLE".equals(b.get("channel"))) return cand;
                    if (fb == null) fb = cand;
                }
                return fb;
            }
            case "purpur" -> {
                String latest = Json.str(Json.obj(Json.obj(Net.getJson(PURPUR + "/" + enc(v))).get("builds")).get("latest"));
                return latest.isEmpty() ? null : new Build(sw, v, latest, PURPUR + "/" + enc(v) + "/" + enc(latest) + "/download");
            }
            case "vanilla" -> {
                for (Object o : Json.list(Json.obj(Net.getJson(MANIFEST)).get("versions"))) {
                    Map<String, Object> e = Json.obj(o);
                    if (v.equals(e.get("id"))) {
                        String url = Json.str(Json.obj(Json.obj(Json.obj(Net.getJson(Json.str(e.get("url")))).get("downloads")).get("server")).get("url"));
                        return url.isEmpty() ? null : new Build(sw, v, "release", url);
                    }
                }
                return null;
            }
            default -> {
                String loader = stable(Net.getJson(FABRIC + "loader")), inst = stable(Net.getJson(FABRIC + "installer"));
                return new Build(sw, v, loader + "-" + inst, FABRIC + "loader/" + enc(v) + "/" + enc(loader) + "/" + enc(inst) + "/server/jar");
            }
        }
    }

    private static String stable(Object list) throws IOException {
        for (Object o : Json.list(list)) {
            Map<String, Object> m = Json.obj(o);
            if (Boolean.TRUE.equals(m.get("stable"))) return Json.str(m.get("version"));
        }
        throw new IOException("No stable Fabric loader/installer found.");
    }
}
