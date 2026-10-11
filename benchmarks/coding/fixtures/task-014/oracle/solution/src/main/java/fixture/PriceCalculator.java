package fixture;
public final class PriceCalculator { public long total(long subtotal,boolean member){long discounted=discount(subtotal,member);return discounted+shipping(discounted);} private long discount(long subtotal,boolean member){return member?subtotal-subtotal/10:subtotal;} private long shipping(long discounted){return discounted<5000?500:0;} }
