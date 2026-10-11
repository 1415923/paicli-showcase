package fixture;

public final class CheckoutService {
    private final FeatureFlags flags;
    public CheckoutService(FeatureFlags flags) { this.flags = flags; }
    public String mode() { return flags.fastCheckout() ? "fast" : "standard"; }
}
