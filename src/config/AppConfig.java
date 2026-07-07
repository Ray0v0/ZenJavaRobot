package config;

import java.util.Collections;
import java.util.List;

/**
 * Top-level application configuration. Loaded from config.properties.
 */
public class AppConfig {

    private final OneBotConfig onebot;
    private final List<Long> admins;
    private final boolean sendEnabled;
    private final List<String> plugins;

    public AppConfig(OneBotConfig onebot, List<Long> admins,
                     boolean sendEnabled, List<String> plugins) {
        this.onebot = onebot != null ? onebot : new OneBotConfig(null, 0, null);
        this.admins = admins != null ? Collections.unmodifiableList(admins) : Collections.emptyList();
        this.sendEnabled = sendEnabled;
        this.plugins = plugins != null ? Collections.unmodifiableList(plugins) : Collections.emptyList();
    }

    public OneBotConfig getOnebot() { return onebot; }
    public List<Long> getAdmins() { return admins; }
    public boolean isSendEnabled() { return sendEnabled; }
    public List<String> getPlugins() { return plugins; }

    @Override
    public String toString() {
        return "AppConfig{onebot=" + onebot + ", admins=" + admins
                + ", sendEnabled=" + sendEnabled + ", plugins=" + plugins + "}";
    }
}
