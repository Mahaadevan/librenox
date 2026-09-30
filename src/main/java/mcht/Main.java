package mcht;

import java.awt.Desktop;
import java.net.URI;

public final class Main {
    private static final String HELP = """
            Minecraft Server Hosting Tool (Java) - web dashboard

              java -jar mc-host.jar [options]

              --port N       web UI port (default 8765)
              --bind ADDR    address to listen on (default 127.0.0.1; anything else exposes
                             full server control to your network - use with care)
              --no-browser   do not open the browser automatically
              -h, --help     show this help
            """;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int port = 8765;
        String bind = "127.0.0.1";
        boolean browser = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> { System.out.print(HELP); return; }
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--bind" -> bind = args[++i];
                case "--no-browser" -> browser = false;
                default -> { System.err.println("Unknown option: " + args[i]); System.err.print(HELP); return; }
            }
        }
        Dirs.ensure();
        Registry reg = new Registry();
        WebServer web = new WebServer(reg, bind, port);
        Runtime.getRuntime().addShutdownHook(new Thread(reg::shutdown, "shutdown"));
        web.start();
        String url = "http://" + (bind.equals("127.0.0.1") ? "localhost" : bind) + ":" + port + "/";
        System.out.println("Minecraft Server Hosting Tool running at " + url);
        System.out.println("Data folder: " + Dirs.BASE);
        if (!bind.equals("127.0.0.1") && !bind.equals("localhost"))
            System.out.println("WARNING: listening on " + bind + " - anyone who can reach this port controls your servers.");
        System.out.println("Press Ctrl+C (or use Exit in the UI) to stop.");
        if (browser) openBrowser(url);
        reg.autostart();
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Throwable ignored) {}
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("linux")) new ProcessBuilder("xdg-open", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
        } catch (Throwable ignored) {}
    }
}
