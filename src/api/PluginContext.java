package api;

import onebot.OneBotAPI;

import java.io.File;

/**
 * Plugin execution context, injected via {@link Plugin#onEnable(PluginContext)}.
 *
 * <p>Each plugin gets its own {@code PluginContext} with a dedicated data
 * directory ({@code plugins/<plugin-name>/}).</p>
 */
public class PluginContext {

    private final OneBotAPI api;
    private final File dataDir;

    public PluginContext(OneBotAPI api, File dataDir) {
        this.api = api;
        this.dataDir = dataDir;
    }

    /** The OneBot API for sending messages and calling QQ actions. */
    public OneBotAPI api() { return api; }

    /** Plugin-private data directory, e.g. {@code plugins/echo/}. */
    public File dataDir() { return dataDir; }
}
