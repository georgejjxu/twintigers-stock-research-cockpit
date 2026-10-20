package ca.twintigers.research.web;

import ca.twintigers.research.adapters.Wiring;
import ca.twintigers.research.adapters.config.Settings;

/**
 * Starts the Stock Research Cockpit on http://localhost:8099 ({@code --port N}). {@code --bind 0.0.0.0} is for the Docker
 * container only, whose port is published to the PC's 127.0.0.1 only. Stop with Ctrl+C.
 */
public final class WebMain {

    public static void main(String[] args) throws Exception {
        int port = 8099;
        java.net.InetAddress bind = java.net.InetAddress.getLoopbackAddress();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--port")) {
                port = Integer.parseInt(args[i + 1]);
            } else if (args[i].equals("--bind")) {
                bind = java.net.InetAddress.getByName(args[i + 1]);
            }
        }
        Wiring w = new Wiring(Settings.load());
        CockpitServer server = new CockpitServer(new CockpitServer.Deps(w.cockpit, w.alerts, w.dispatcher::channels,
                () -> w.alertDb.recentRuns(15), () -> w.alertDb.recentEntries(15), w.prices.engine().rules(), w.writer, w.yahoo, w.intraday, w.markets), bind, port);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("Stock Research Cockpit: http://localhost:" + server.port() + "/  (holdings source: " + w.portfolio.name() + ")");
    }
}
