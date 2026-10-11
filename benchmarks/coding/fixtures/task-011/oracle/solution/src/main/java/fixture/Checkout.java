package fixture;
public final class Checkout { private final DiscountPolicy policy; public Checkout(DiscountPolicy policy){this.policy=policy;} public long total(long subtotal,boolean member){return Math.max(0,subtotal-policy.discountCents(subtotal,member));} }
