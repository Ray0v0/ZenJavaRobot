package config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads {@link AppConfig} from a {@code .properties} file, falling back to
 * defaults when the file does not exist.
 */
public class ConfigLoader {

    private static final Logger LOG = Logger.getLogger(ConfigLoader.class.getName());

    private ConfigLoader() { /* utility class */ }

    /**
     * Load configuration from a properties file path.
     *
     * @param path path to config.properties
     * @return the parsed {@link AppConfig}, never null
     * @throws IOException if the file exists but cannot be read/parsed
     */
    public static AppConfig load(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            LOG.warning(String.format("Config file '%s' not found, using defaults", path));
            return new AppConfig(null, null, false, null);
        }

        LOG.info(String.format("Loading config from '%s'", file.getAbsolutePath()));

        Properties props = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        }

        // Parse onebot config
        OneBotConfig onebot = new OneBotConfig(
                props.getProperty("onebot.host", "0.0.0.0"),
                Integer.parseInt(props.getProperty("onebot.port", "6198")),
                props.getProperty("onebot.access_token", "")
        );

        // Parse admins (comma-separated QQ numbers)
        List<Long> admins = new ArrayList<>();
        String adminsStr = props.getProperty("admins", "").trim();
        if (!adminsStr.isEmpty()) {
            for (String part : adminsStr.split(",")) {
                try {
                    admins.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException e) {
                    LOG.warning(String.format("Invalid admin QQ number: '%s'", part.trim()));
                }
            }
        }

        // Parse other settings
        boolean sendEnabled = Boolean.parseBoolean(props.getProperty("send_enabled", "false"));

        // Parse plugins (comma-separated JAR filenames)
        List<String> plugins = new ArrayList<>();
        String pluginsStr = props.getProperty("plugins", "").trim();
        if (!pluginsStr.isEmpty()) {
            for (String part : pluginsStr.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    plugins.add(trimmed);
                }
            }
        }

        AppConfig config = new AppConfig(onebot, admins, sendEnabled, plugins);
        LOG.info(String.format("Config loaded: %s", config));
        return config;
    }
}
