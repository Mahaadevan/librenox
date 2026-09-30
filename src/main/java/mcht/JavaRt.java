package mcht;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/** Java runtime discovery. */
final class JavaRt {
    private JavaRt() {}

    static final String NAME = Util.IS_WIN ? "java.exe" : "java";
    private static final Pattern VER = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?");

    static int major(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            Matcher m = VER.matcher(out);
            if (!m.find()) return 0;
            int major = Integer.parseInt(m.group(1));
            return major == 1 ? (m.group(2) == null ? 0 : Integer.parseInt(m.group(2))) : major;
        } catch (Exception e) {
            return 0;
        }
    }

    static int required(String mc) {
        List<Integer> n = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(mc);
        while (m.find()) n.add(Integer.parseInt(m.group()));
        if (n.isEmpty()) return 21;
        if (n.get(0) >= 26) return 25;
        int minor = n.size() > 1 ? n.get(1) : 0, patch = n.size() > 2 ? n.get(2) : 0;
        return minor >= 21 || (minor == 20 && patch >= 5) ? 21 : 17;
    }

    static Path find(Path folder) throws IOException {
        try (Stream<Path> w = Files.walk(folder)) {
            return w.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equals(NAME)
                    && p.getParent().getFileName().toString().equals("bin")).findFirst().orElse(null);
        }
    }

    /** Private runtimes first, then JAVA_HOME, PATH and the JVM running this tool. */
    static String locate(int min) {
        List<String> c = new ArrayList<>();
        try (Stream<Path> s = Files.exists(Dirs.RUNTIME) ? Files.list(Dirs.RUNTIME) : Stream.<Path>empty()) {
            for (Path d : (Iterable<Path>) s.filter(p -> p.getFileName().toString().startsWith("jre-")).sorted()::iterator) {
                Path exe = find(d);
                if (exe != null) c.add(exe.toString());
            }
        } catch (IOException ignored) {}
        String home = System.getenv("JAVA_HOME");
        if (home != null && Files.exists(Path.of(home, "bin", NAME))) c.add(Path.of(home, "bin", NAME).toString());
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path p = Path.of(dir, NAME);
                if (Files.isExecutable(p)) { c.add(p.toString()); break; }
            }
        }
        Path own = Path.of(System.getProperty("java.home"), "bin", NAME);
        if (Files.exists(own)) c.add(own.toString());
        for (String x : c) if (major(x) >= min) return x;
        return c.isEmpty() ? null : c.get(0);
    }
}
