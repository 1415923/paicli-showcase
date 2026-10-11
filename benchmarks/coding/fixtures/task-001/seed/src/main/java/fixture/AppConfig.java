package fixture;

public final class AppConfig {
    public int httpPort() {
        return Integer.parseInt(System.getenv().getOrDefault("PAICLI_PORT", "8080"));
    }
}
