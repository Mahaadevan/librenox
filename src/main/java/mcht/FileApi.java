package mcht;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Sandboxed file manager, worlds and log files for one server folder. */
final class FileApi {
    private FileApi() {}

    static Path res(Path root, String rel) {
        String r = rel == null ? "" : rel.replace('\\', '/');
        while (r.startsWith("/")) r = r.substring(1);
        Path p = root.resolve(r).normalize();
        if (!p.startsWith(root.normalize())) throw new ApiError(400, "Invalid path.");
        try {
            Path real = Files.exists(p) ? p.toRealPath() : p.getParent() != null && Files.exists(p.getParent()) ? p.getParent().toRealPath() : null;
            if (real != null && !real.startsWith(root.toRealPath())) throw new ApiError(400, "Invalid path.");
        } catch (IOException ignored) {}
        return p;
    }

    private static String plainName(String n) {
        if (n == null || n.isBlank() || n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..") || n.length() > 200 || n.matches(".*\\p{Cntrl}.*"))
            throw new ApiError(400, "Invalid name.");
        return n;
    }

    static List<Map<String, Object>> list(Path root, String rel) throws IOException {
        Path d = res(root, rel);
        if (!Files.isDirectory(d)) throw new ApiError(404, "Folder not found.");
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.comparing((Path x) -> !Files.isDirectory(x)).thenComparing(x -> x.getFileName().toString().toLowerCase()))::iterator) {
                Map<String, Object> m = new LinkedHashMap<>();
                boolean dir = Files.isDirectory(p);
                m.put("name", p.getFileName().toString());
                m.put("dir", dir);
                m.put("size", dir ? 0 : Files.size(p));
                m.put("modified", Files.getLastModifiedTime(p).toMillis());
                out.add(m);
            }
        }
        return out;
    }

    static String read(Path root, String rel) throws IOException {
        Path f = res(root, rel);
        if (!Files.isRegularFile(f)) throw new ApiError(404, "File not found.");
        if (Files.size(f) > 2_000_000) throw new ApiError(413, "File is too large to edit here (2 MB max). Download it instead.");
        byte[] b = Files.readAllBytes(f);
        for (int i = 0; i < Math.min(b.length, 8192); i++) if (b[i] == 0) throw new ApiError(415, "This looks like a binary file.");
        return new String(b, StandardCharsets.UTF_8);
    }

    static void write(Path root, String rel, String content) throws IOException {
        Path f = res(root, rel);
        if (Files.isDirectory(f)) throw new ApiError(400, "That is a folder.");
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
    }

    static void mkdir(Path root, String rel) throws IOException { Files.createDirectories(res(root, rel)); }

    static void delete(Path root, String rel) throws IOException {
        Path p = res(root, rel);
        if (p.equals(root.normalize())) throw new ApiError(400, "Cannot delete the server folder.");
        Util.deleteTree(p);
    }

    static void rename(Path root, String rel, String newName) throws IOException {
        Path p = res(root, rel);
        if (p.equals(root.normalize())) throw new ApiError(400, "Cannot rename the server folder.");
        Path t = res(root, root.relativize(p.getParent()) + "/" + plainName(newName));
        if (Files.exists(t)) throw new ApiError(409, "Something with that name already exists.");
        Files.move(p, t);
    }

    static void unzip(Path root, String rel) throws IOException {
        Path z = res(root, rel);
        if (!Files.isRegularFile(z) || !z.getFileName().toString().toLowerCase().endsWith(".zip")) throw new ApiError(400, "Not a .zip file.");
        Archive.unzip(z, z.getParent());
    }

    static long size(Path d) {
        try (Stream<Path> w = Files.walk(d)) {
            return w.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
        } catch (IOException e) { return 0; }
    }

    static List<Map<String, Object>> worlds(Path root) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : (Iterable<Path>) s.filter(x -> Files.isDirectory(x) && Files.exists(x.resolve("level.dat"))).sorted()::iterator) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getFileName().toString());
                m.put("size", size(p));
                m.put("modified", Files.getLastModifiedTime(p.resolve("level.dat")).toMillis());
                out.add(m);
            }
        }
        return out;
    }

    static void zipDir(Path dir, OutputStream os) throws IOException {
        Path parent = dir.getParent();
        try (ZipOutputStream z = new ZipOutputStream(os); Stream<Path> w = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                if (p.getFileName().toString().equals("session.lock")) continue;
                try {
                    z.putNextEntry(new ZipEntry(parent.relativize(p).toString().replace('\\', '/')));
                    Files.copy(p, z);
                    z.closeEntry();
                } catch (IOException ignored) {}
            }
        }
    }

    static List<String> logFiles(Path root) throws IOException {
        Path d = root.resolve("logs");
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.list(d)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".log") || n.endsWith(".log.gz")).forEach(out::add);
        }
        out.sort(Comparator.reverseOrder());
        out.remove("latest.log");
        out.add(0, "latest.log");
        if (!Files.exists(d.resolve("latest.log"))) out.remove("latest.log");
        return out;
    }

    static String logText(Path root, String name) throws IOException {
        Path f = res(root, "logs/" + plainName(name));
        if (!Files.isRegularFile(f)) throw new ApiError(404, "Log not found.");
        byte[] b;
        try (InputStream in = name.endsWith(".gz") ? new java.util.zip.GZIPInputStream(Files.newInputStream(f)) : Files.newInputStream(f)) {
            b = in.readAllBytes();
        }
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.length() > 1_500_000) s = "... (older lines cut) ...\n" + s.substring(s.length() - 1_500_000);
        return s;
    }
}
