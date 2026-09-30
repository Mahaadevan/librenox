package mcht;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Modrinth API client (plugins, mods, datapacks). */
final class Modrinth {
    private Modrinth() {}

    static final String API = "https://api.modrinth.com/v2";

    record Jar(String url, String filename) {}

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static List<Map<String, Object>> search(String query, String type, String version, List<String> loaders) throws Exception {
        List<Object> facets = new ArrayList<>();
        facets.add(List.of("project_type:" + type));
        if (!type.equals("datapack") && !loaders.isEmpty()) facets.add(loaders.stream().map(l -> "categories:" + l).toList());
        if (!version.isEmpty()) facets.add(List.of("versions:" + version));
        String url = API + "/search?query=" + enc(query) + "&limit=10&facets=" + enc(Json.stringify(facets));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.list(Json.obj(Net.getJson(url)).get("hits"))) {
            Map<String, Object> h = Json.obj(o), r = new LinkedHashMap<>();
            r.put("project_id", Json.str(h.get("project_id")));
            r.put("title", Json.str(h.get("title")));
            r.put("description", Json.str(h.get("description")));
            r.put("icon_url", Json.str(h.get("icon_url")));
            r.put("downloads", Json.num(h.get("downloads")));
            r.put("author", Json.str(h.get("author")));
            out.add(r);
        }
        return out;
    }

    static Jar newest(String projectId, List<String> loaders, String version, String ext) throws Exception {
        String url = API + "/project/" + enc(projectId) + "/version?loaders=" + enc(Json.stringify(loaders));
        if (!version.isEmpty()) url += "&game_versions=" + enc(Json.stringify(List.of(version)));
        for (Object vo : Json.list(Net.getJson(url))) {
            List<Map<String, Object>> files = new ArrayList<>();
            for (Object fo : Json.list(Json.obj(vo).get("files"))) {
                Map<String, Object> f = Json.obj(fo);
                if (Json.str(f.get("filename")).endsWith(ext)) files.add(f);
            }
            if (files.isEmpty()) continue;
            Map<String, Object> pick = files.stream().filter(f -> Boolean.TRUE.equals(f.get("primary"))).findFirst().orElse(files.get(0));
            String u = Json.str(pick.get("url"));
            if (!u.startsWith("https://")) throw new IOException("Unexpected download URL.");
            return new Jar(u, Json.str(pick.get("filename")));
        }
        throw new IOException("No compatible file was published for this project" + (version.isEmpty() ? "." : " (Minecraft " + version + ")."));
    }
}
