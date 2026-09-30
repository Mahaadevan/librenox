package mcht;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** server.properties reader/writer that preserves comments, order and unknown keys. */
final class Props {
    private Props() {}

    private static final Map<Path, Object[]> CACHE = new HashMap<>();

    static String unesc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'u' && i + 4 < s.length() + 0 && s.substring(i + 1, Math.min(s.length(), i + 5)).matches("[0-9a-fA-F]{4}")) {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                } else if (n == 'n') b.append('\n');
                else if (n == 't') b.append('\t');
                else b.append(n);
            } else b.append(c);
        }
        return b.toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c > 126) b.append(String.format("\\u%04X", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    static synchronized Map<String, String> read(Path f) {
        try {
            if (!Files.exists(f)) return new LinkedHashMap<>();
            long mt = Files.getLastModifiedTime(f).toMillis();
            Object[] c = CACHE.get(f);
            if (c != null && (long) c[0] == mt) {
                @SuppressWarnings("unchecked") Map<String, String> cached = (Map<String, String>) c[1];
                return new LinkedHashMap<>(cached);
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : Files.readAllLines(f, StandardCharsets.ISO_8859_1)) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) continue;
                int i = line.indexOf('=');
                if (i < 1) continue;
                m.put(line.substring(0, i).trim(), unesc(line.substring(i + 1).stripLeading()));
            }
            CACHE.put(f, new Object[]{mt, new LinkedHashMap<>(m)});
            return m;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    static String get(Path f, String k, String def) { return read(f).getOrDefault(k, def); }

    static synchronized void update(Path f, Map<String, String> changes) throws IOException {
        for (Map.Entry<String, String> e : changes.entrySet()) {
            if (!e.getKey().matches("[A-Za-z0-9._\\-]{1,64}")) throw new ApiError(400, "Invalid property name: " + e.getKey());
            if (e.getValue().length() > 4000 || e.getValue().matches("(?s).*[\\x00-\\x09\\x0B-\\x1F].*")) throw new ApiError(400, "Invalid value for " + e.getKey() + ".");
        }
        List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.ISO_8859_1)) : new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            int k = l.indexOf('=');
            if (l.startsWith("#") || k < 1) continue;
            String key = l.substring(0, k).trim();
            if (changes.containsKey(key)) { lines.set(i, key + "=" + esc(changes.get(key))); done.add(key); }
        }
        for (Map.Entry<String, String> e : changes.entrySet()) if (!done.contains(e.getKey())) lines.add(e.getKey() + "=" + esc(e.getValue()));
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.ISO_8859_1);
        CACHE.remove(f);
    }
}
