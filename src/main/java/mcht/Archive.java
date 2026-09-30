package mcht;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

/** zip and tar.gz extraction using only the JDK (with path-traversal protection). */
final class Archive {
    private Archive() {}

    static void extract(Path archive, Path dest) throws IOException {
        Util.deleteTree(dest);
        Files.createDirectories(dest);
        if (archive.getFileName().toString().endsWith(".zip")) unzip(archive, dest);
        else untar(archive, dest);
    }

    static void unzip(Path archive, Path dest) throws IOException {
        Path root = dest.normalize();
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) continue;
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void untar(Path archive, Path dest) throws IOException {
        Path root = dest.normalize();
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            byte[] h = new byte[512];
            String longName = null, paxPath = null;
            while (in.readNBytes(h, 0, 512) == 512) {
                if (isZero(h)) break;
                String name = cstr(h, 0, 100);
                long size = octal(h, 124, 12);
                int mode = (int) octal(h, 100, 8);
                char type = h[156] == 0 ? '0' : (char) h[156];
                if (cstr(h, 257, 6).startsWith("ustar")) {
                    String prefix = cstr(h, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }
                long pad = (size + 511) / 512 * 512 - size;
                if (type == 'L' || type == 'x') {
                    byte[] data = in.readNBytes((int) size);
                    skip(in, pad);
                    if (type == 'L') longName = cstr(data, 0, data.length);
                    else paxPath = paxPath(data);
                    continue;
                }
                if (type == 'g') { skip(in, size + pad); continue; }
                if (longName != null) { name = longName; longName = null; }
                if (paxPath != null) { name = paxPath; paxPath = null; }
                Path out = dest.resolve(name).normalize();
                if (!out.startsWith(root)) { skip(in, size + pad); continue; }
                if (type == '5') {
                    Files.createDirectories(out);
                    skip(in, size + pad);
                } else if (type == '0' || type == '7') {
                    Files.createDirectories(out.getParent());
                    try (OutputStream o = Files.newOutputStream(out)) {
                        long left = size;
                        byte[] buf = new byte[65536];
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new EOFException("Truncated archive");
                            o.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    skip(in, pad);
                    if ((mode & 0100) != 0) out.toFile().setExecutable(true);
                } else {
                    skip(in, size + pad); // symlinks/hardlinks are not needed for a JRE
                }
            }
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            n -= s;
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // base-256 for very large values
            long v = b[off] & 0x7f;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xff);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i];
            if (c >= '0' && c <= '7') v = v * 8 + (c - '0');
            else if (v > 0 || c == 0) break;
        }
        return v;
    }

    private static String paxPath(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        for (String rec : s.split("\n")) {
            int sp = rec.indexOf(' ');
            if (sp > 0 && rec.startsWith("path=", sp + 1)) return rec.substring(sp + 6);
        }
        return null;
    }
}
