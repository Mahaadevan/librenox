package mcht;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class Util {
    private Util() {}

    static final boolean IS_WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-9;?]*[A-Za-z]|\\x1b\\][^\\x07]*\\x07");
    static final Map<String, String> ENV = loadEnv();

    private static String lan = "127.0.0.1";
    private static long lanAt;

    static boolean isArm() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        return a.contains("aarch64") || a.contains("arm64");
    }

    static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static synchronized String lanIp() {
        long now = System.currentTimeMillis();
        if (now - lanAt > 10_000) {
            lanAt = now;
            try (DatagramSocket s = new DatagramSocket()) {
                s.connect(InetAddress.getByName("10.255.255.255"), 1); // no packet is sent
                String a = s.getLocalAddress().getHostAddress();
                lan = a.startsWith("0.") ? "127.0.0.1" : a;
            } catch (Exception e) {
                lan = "127.0.0.1";
            }
        }
        return lan;
    }

    static String msg(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    static String hms(long sec) {
        sec = Math.max(0, sec);
        return String.format("%02d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60);
    }

    /** Resolve a plain file name inside dir; rejects anything that could escape it. */
    static Path safeChild(Path dir, String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        if (n.isBlank() || n.equals(".") || n.equals("..")) throw new ApiError(400, "Invalid file name.");
        Path p = dir.resolve(n).normalize();
        if (!p.startsWith(dir.normalize())) throw new ApiError(400, "Invalid file name.");
        return p;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : (Iterable<Path>) w.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }

    private static Map<String, String> loadEnv() {
        Map<String, String> m = new HashMap<>();
        Path f = Dirs.ROOT.resolve(".env");
        try {
            if (Files.exists(f)) {
                for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                    int i = line.indexOf('=');
                    String k = line.substring(0, i).strip();
                    String v = line.substring(i + 1).strip().replaceAll("^[\"']|[\"']$", "");
                    if (!k.isEmpty()) m.put(k, v);
                }
            }
        } catch (IOException ignored) {}
        return m;
    }

    static String env(String key) {
        String v = System.getenv(key);
        return v != null && !v.isEmpty() ? v : ENV.getOrDefault(key, "");
    }
}
