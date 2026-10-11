package fixture;
public final class StandardDiscountPolicy implements DiscountPolicy { public long discountCents(long subtotalCents,boolean member) { return member ? Math.max(0,subtotalCents)/10 : 0; } }
