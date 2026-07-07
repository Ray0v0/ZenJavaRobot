package app;

import api.MessageHandler;
import api.Plugin;
import config.AppConfig;
import config.ConfigLoader;
import onebot.OneBotAPI;
import onebot.OneBotServer;
import onebot.model.OneBotEvent;
import plugin.PluginManager;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ZenJavaRobot — minimal OneBot v11 QQ robot in Java.
 *
 * <p>Entry point that loads configuration, starts the WebSocket server,
 * and runs the event dispatch loop until interrupted.</p>
 *
 * <p>Usage:
 * <pre>{@code
 *   java -cp out app.App                        # uses ./config.properties
 *   java -cp out app.App -c /path/to/config.properties
 *   java -cp out app.App -v                     # FINE logging
 * }</pre>
 */
public class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    private final AppConfig config;
    private final OneBotServer server;
    private final OneBotAPI api;
    private final PluginManager pluginManager;
    private final List<MessageHandler> handlers = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "event-dispatch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public App(AppConfig config) {
        this.config = config;
        this.server = new OneBotServer(config.getOnebot());
        this.api = new OneBotAPI(server, config.isSendEnabled());
        this.pluginManager = new PluginManager(new File("plugins"));
    }

    /**
     * Register a message handler.
     */
    public void registerHandler(MessageHandler handler) {
        handlers.add(handler);
    }

    /**
     * Start the server and the event dispatch loop.
     */
    public void start() {
        // Load plugins from plugins/ directory (ordered by config.properties)
        pluginManager.loadPlugins(api, config.getPlugins());
        List<Plugin> plugins = pluginManager.getPlugins();
        for (Plugin plugin : plugins) {
            registerHandler(plugin);
        }
        LOG.info(String.format("%d plugin(s) loaded", plugins.size()));

        LOG.info("Starting ZenJavaRobot...");
        LOG.info(String.format("sendEnabled=%s — messages will %sbe sent to QQ",
                api.isSendEnabled(), api.isSendEnabled() ? "" : "NOT "));

        try {
            server.start();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, String.format("Failed to start WebSocket server: %s", e.getMessage()), e);
            return;
        }
        running.set(true);

        // Start the event dispatch thread
        dispatchExecutor.submit(this::dispatchLoop);

        LOG.info("ZenJavaRobot started. Waiting for QQ client to connect...");
    }

    /**
     * Stop the server, the dispatch loop, and clean up.
     */
    public void stop() {
        LOG.info("Shutting down ZenJavaRobot...");
        running.set(false);

        pluginManager.disableAll();

        dispatchExecutor.shutdownNow();
        try {
            dispatchExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            server.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning("Interrupted while stopping server");
        }

        LOG.info("ZenJavaRobot stopped.");
    }

    /**
     * Main dispatch loop: pull events from the server queue and dispatch to handlers.
     */
    private void dispatchLoop() {
        LOG.info(String.format("Event dispatch loop started (thread: %s)", Thread.currentThread().getName()));

        while (running.get()) {
            try {
                // Block up to 1 second to check running flag periodically
                OneBotEvent event = server.takeEvent();

                // Dispatch to handlers in order; stop when one returns true
                for (MessageHandler handler : handlers) {
                    try {
                        if (handler.onEvent(event)) {
                            break; // event handled, stop propagation
                        }
                    } catch (Exception e) {
                        LOG.log(Level.SEVERE,
                                String.format("Handler %s threw exception: %s",
                                        handler.getClass().getSimpleName(), e.getMessage()));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("Error in dispatch loop: %s", e.getMessage()), e);
            }
        }

        LOG.info("Event dispatch loop stopped.");
    }

    // =====================================================================
    // Entry point
    // =====================================================================

    public static void main(String[] args) {
        // Set JUL log format to match old logback pattern: HH:mm:ss.SSS [thread] LEVEL logger - msg
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tH:%1$tM:%1$tS.%1$tL [%4$-5s] %3$s - %5$s%6$s%n");

        // Parse CLI arguments
        String configPath = "config.properties";
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-c":
                case "--config":
                    if (i + 1 < args.length) configPath = args[++i];
                    else {
                        System.err.println("Error: -c/--config requires a path argument");
                        System.exit(1);
                    }
                    break;
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "-h":
                case "--help":
                    System.out.println("ZenJavaRobot — Minimal OneBot v11 QQ robot in Java");
                    System.out.println();
                    System.out.println("Usage: java -cp out app.App [options]");
                    System.out.println();
                    System.out.println("Options:");
                    System.out.println("  -c, --config <path>   Path to config.properties (default: ./config.properties)");
                    System.out.println("  -v, --verbose         Enable FINE logging");
                    System.out.println("  -h, --help            Show this help");
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    System.err.println("Use -h for help");
                    System.exit(1);
            }
        }

        // Configure logging level
        if (verbose) {
            Logger rootLogger = Logger.getLogger("");
            rootLogger.setLevel(Level.FINE);
            for (Handler handler : rootLogger.getHandlers()) {
                handler.setLevel(Level.FINE);
            }
        }

        // Load configuration
        AppConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, String.format("Failed to load config: %s", e.getMessage()));
            System.exit(1);
            return;
        }

        // Create and start the application
        App app = new App(config);

        // Register shutdown hook for graceful stop
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.stop();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("Error during shutdown: %s", e.getMessage()));
            }
        }, "shutdown-hook"));

        try {
            app.start();

            // Block the main thread indefinitely
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, String.format("Fatal error: %s", e.getMessage()), e);
            System.exit(1);
        }
    }
}
