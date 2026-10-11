package fixture;

public final class FeatureFlags {
    public boolean fastCheckout() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("FAST_CHECKOUT", "false"));
    }
}
