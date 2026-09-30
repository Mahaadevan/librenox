package mcht;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.DoubleConsumer;

final class Net {
    private Net() {}

    static final String UA = "minecraft-server-hosting-tool-java/1.0";
    private static final HttpClient HC = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();

    static Object getJson(String url) throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA)
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp = HC.send(r, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode() + " from " + URI.create(url).getHost());
        return Json.parse(resp.body());
    }

    /** progress receives a 0..1 fraction, or -1 when the size is unknown. */
    static void download(String url, Path target, DoubleConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).GET().build();
        HttpResponse<InputStream> resp = HC.send(r, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) throw new IOException("Download failed: HTTP " + resp.statusCode());
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(0);
        long done = 0;
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.accept(total > 0 ? (double) done / total : -1);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
