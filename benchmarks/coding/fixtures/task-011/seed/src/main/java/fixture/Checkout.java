package fixture;
public final class Checkout { private final DiscountPolicy policy; public Checkout(DiscountPolicy policy){this.policy=policy;} public long total(long subtotal){return subtotal-policy.discountCents(subtotal);} }
