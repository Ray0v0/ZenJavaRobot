package config;

/**
 * Settings for the OneBot v11 reverse-WebSocket server.
 */
public class OneBotConfig {

    private final String host;
    private final int port;
    private final String accessToken;

    public OneBotConfig(String host, int port, String accessToken) {
        this.host = (host != null && !host.isBlank()) ? host : "0.0.0.0";
        this.port = port > 0 ? port : 6198;
        this.accessToken = accessToken != null ? accessToken : "";
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getAccessToken() { return accessToken; }

    @Override
    public String toString() {
        return "OneBotConfig{host='" + host + "', port=" + port + "}";
    }
}
