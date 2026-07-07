package plugin;

import api.Plugin;
import api.PluginContext;
import onebot.OneBotAPI;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovers, loads, and manages plugin JARs from the {@code plugins/} directory.
 *
 * <p>Load order follows the plugin list in {@code config.yaml}.  Each plugin JAR
 * gets its own {@link URLClassLoader} whose parent is the application classloader,
 * so plugins can reference ZenJavaRobot classes.  Plugins from different JARs
 * are isolated from each other.</p>
 */
public class PluginManager {

    private static final Logger LOG = Logger.getLogger(PluginManager.class.getName());

    private final File pluginsDir;
    private final List<LoadedPlugin> loaded = new ArrayList<>();

    public PluginManager(File pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    /**
     * Load plugins in the order specified by {@code jarNames}.
     *
     * @param api      the OneBot API injected into each plugin context
     * @param jarNames list of JAR filenames (e.g. {@code ["echo-plugin.jar"]}),
     *                 relative to {@code pluginsDir}
     */
    public void loadPlugins(OneBotAPI api, List<String> jarNames) {
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs();
        }

        for (String jarName : jarNames) {
            if (jarName == null || jarName.isBlank()) continue;

            File jarFile = new File(pluginsDir, jarName);
            if (!jarFile.exists()) {
                LOG.warning(String.format("Plugin JAR not found, skipping: %s", jarFile.getPath()));
                continue;
            }

            try {
                loadSingleJar(api, jarFile);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("Failed to load plugin from %s: %s", jarName, e.getMessage()), e);
            }
        }
    }

    private void loadSingleJar(OneBotAPI api, File jarFile) throws Exception {
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarFile.toURI().toURL()},
                getClass().getClassLoader()   // parent = application CL
        );

        // Discover Plugin implementations via Java SPI
        ServiceLoader<Plugin> serviceLoader = ServiceLoader.load(Plugin.class, classLoader);

        int count = 0;
        for (Plugin plugin : serviceLoader) {
            // Data dir derived from JAR name: echo-plugin.jar → plugins/echo-plugin/data/
            String jarBase = jarFile.getName().replaceAll("\\.jar$", "");
            File dataDir = new File(new File(pluginsDir, jarBase), "data");

            PluginContext ctx = new PluginContext(api, dataDir);

            plugin.onLoad();
            plugin.onEnable(ctx);

            loaded.add(new LoadedPlugin(plugin, classLoader));
            LOG.info(String.format("Plugin loaded: %s v%s (from %s)",
                    plugin.name(), plugin.version(), jarFile.getName()));
            count++;
        }

        if (count == 0) {
            // No Plugin implementation found in this JAR — close the classloader
            classLoader.close();
            LOG.warning(String.format("No Plugin implementation found in %s", jarFile.getName()));
        }
    }

    /**
     * Returns all successfully-loaded {@link Plugin} instances in load order.
     * Each plugin is also a {@link api.MessageHandler}.
     */
    public List<Plugin> getPlugins() {
        List<Plugin> result = new ArrayList<>();
        for (LoadedPlugin lp : loaded) {
            result.add(lp.plugin);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Disable all loaded plugins and close their classloaders.
     */
    public void disableAll() {
        // Disable in reverse load order
        for (int i = loaded.size() - 1; i >= 0; i--) {
            LoadedPlugin lp = loaded.get(i);
            try {
                lp.plugin.onDisable();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("Error disabling plugin '%s': %s", lp.plugin.name(), e.getMessage()));
            }
        }
        for (LoadedPlugin lp : loaded) {
            try {
                lp.classLoader.close();
            } catch (Exception e) {
                LOG.warning(String.format("Error closing classloader for '%s': %s", lp.plugin.name(), e.getMessage()));
            }
        }
        loaded.clear();
    }

    /**
     * Internal record of a loaded plugin and its classloader.
     */
    private static class LoadedPlugin {
        final Plugin plugin;
        final URLClassLoader classLoader;

        LoadedPlugin(Plugin plugin, URLClassLoader classLoader) {
            this.plugin = plugin;
            this.classLoader = classLoader;
        }
    }
}
