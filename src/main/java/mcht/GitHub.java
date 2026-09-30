package mcht;

import java.io.IOException;
import java.util.*;

/** GitHub release asset lookup for tunnel helper binaries. */
final class GitHub {
    private GitHub() {}

    static Map<String, Object> releaseAsset(String repo, String name) throws Exception {
        Map<String, Object> rel = Json.obj(Net.getJson("https://api.github.com/repos/" + repo + "/releases/latest"));
        String os = Util.IS_WIN ? "windows" : Util.IS_MAC ? "darwin" : "linux";
        List<String> arch = Util.isArm() ? List.of("aarch64", "arm64") : List.of("amd64", "x86_64", "x64", "intel");
        List<String> bad = List.of(".sha256", ".sig", ".txt", ".msi", ".deb", ".rpm", ".asc", ".sha512");
        for (Object o : Json.list(rel.get("assets"))) {
            Map<String, Object> a = Json.obj(o);
            String n = Json.str(a.get("name")).toLowerCase();
            if (n.contains(os) && arch.stream().anyMatch(n::contains) && bad.stream().noneMatch(n::endsWith)) return a;
        }
        throw new IOException("No compatible " + name + " release was found.");
    }
}
