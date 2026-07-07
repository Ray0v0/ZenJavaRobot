package api;

/**
 * Plugin lifecycle interface. Every plugin implements this and is discovered
 * via Java SPI ({@code META-INF/services/api.Plugin}).
 *
 * <p>A {@code Plugin} is also a {@link MessageHandler} — after loading it is
 * registered into the event dispatch pipeline.  Return {@code true} from
 * {@link #onEvent} to stop propagation to later handlers/plugins.</p>
 */
public interface Plugin extends MessageHandler {

    /** Unique plugin identifier, e.g. {@code "echo"}. */
    String name();

    /** Human-readable version string, e.g. {@code "1.0.0"}. */
    String version();

    /** Called once after the plugin class is loaded. */
    default void onLoad() {}

    /**
     * Called when the plugin is activated.  The {@code ctx} provides access
     * to the OneBot API and the plugin's private data directory.
     */
    default void onEnable(PluginContext ctx) {}

    /** Called when the plugin is unloaded.  Release resources here. */
    default void onDisable() {}
}
