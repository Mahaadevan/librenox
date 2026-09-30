package mcht;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.*;

/** server-icon.png handling (64x64, shown in the Minecraft server list too). */
final class Icons {
    private Icons() {}

    private static final Pattern ICON_ENTRY = Pattern.compile("(?i)^(server-icon|icon|pack|logo)\\.png$");

    static Path file(Path dir) { return dir.resolve("server-icon.png"); }

    static void setFromBytes(Path dir, byte[] data) throws IOException {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) throw new ApiError(400, "That is not a valid image.");
            int s = Math.min(src.getWidth(), src.getHeight());
            BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, 64, 64, (src.getWidth() - s) / 2, (src.getHeight() - s) / 2, (src.getWidth() + s) / 2, (src.getHeight() + s) / 2, null);
            g.dispose();
            Files.createDirectories(dir);
            ImageIO.write(out, "png", file(dir).toFile());
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            throw new ApiError(500, "Image support is not available in this Java runtime.");
        }
    }

    static void generate(Path dir, String name) {
        try {
            int hue = Math.abs(name.hashCode()) % 360;
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue / 360f, .55f, .85f), 64, 64, Color.getHSBColor(hue / 360f, .7f, .55f)));
            g.fillRect(0, 0, 64, 64);
            g.setColor(new Color(0, 0, 0, 60));
            g.setStroke(new BasicStroke(4));
            g.drawRect(2, 2, 60, 60);
            try {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
                String t = name.isBlank() ? "M" : name.substring(0, 1).toUpperCase();
                FontMetrics fm = g.getFontMetrics();
                g.setColor(Color.WHITE);
                g.drawString(t, (64 - fm.stringWidth(t)) / 2, (64 - fm.getHeight()) / 2 + fm.getAscent());
            } catch (Throwable ignored) {}
            g.dispose();
            Files.createDirectories(dir);
            ImageIO.write(img, "png", file(dir).toFile());
        } catch (Throwable ignored) {}
    }

    /** Images bundled in server.jar or installed plugin/mod jars. */
    static List<Map<String, Object>> candidates(Path dir) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        jars.add(dir.resolve("server.jar"));
        for (String d : List.of("plugins", "mods")) {
            try (Stream<Path> s = Files.isDirectory(dir.resolve(d)) ? Files.list(dir.resolve(d)) : Stream.<Path>empty()) {
                s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().limit(60).forEach(jars::add);
            } catch (IOException ignored) {}
        }
        for (Path j : jars) {
            if (!Files.isRegularFile(j)) continue;
            try (ZipFile z = new ZipFile(j.toFile())) {
                for (ZipEntry e : Collections.list(z.entries()))
                    if (!e.isDirectory() && ICON_ENTRY.matcher(e.getName()).matches() && e.getSize() < 5_000_000) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("source", dir.relativize(j).toString().replace('\\', '/'));
                        m.put("entry", e.getName());
                        out.add(m);
                    }
            } catch (IOException ignored) {}
        }
        return out;
    }

    static byte[] candidateBytes(Path dir, String source, String entry) throws IOException {
        Path j = FileApi.res(dir, source);
        if (!source.endsWith(".jar") || !ICON_ENTRY.matcher(entry).matches()) throw new ApiError(400, "Not an icon candidate.");
        try (ZipFile z = new ZipFile(j.toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null || e.getSize() > 5_000_000) throw new ApiError(404, "Icon not found in that file.");
            try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
        }
    }

    /** Replace the generated icon with one bundled in the downloaded server jar, if any. */
    static boolean autoFromJar(Path dir) {
        try {
            for (Map<String, Object> c : candidates(dir)) {
                if (!"server.jar".equals(c.get("source"))) continue;
                setFromBytes(dir, candidateBytes(dir, "server.jar", Json.str(c.get("entry"))));
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
