package mcht;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

final class Dirs {
    private Dirs() {}

    static final Path ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    static final Path BASE = ROOT.resolve("minecraft-host");
    static final Path SERVERS = BASE.resolve("servers");
    static final Path BIN = BASE.resolve("bin");
    static final Path RUNTIME = BASE.resolve("runtime");
    static final Path TMP = BASE.resolve("tmp");

    static void ensure() throws IOException {
        for (Path p : List.of(BASE, SERVERS, BIN, RUNTIME, TMP)) Files.createDirectories(p);
    }
}
