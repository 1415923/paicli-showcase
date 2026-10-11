package fixture;
public final class PriceCalculator { public long total(long subtotal,boolean member){ long value=subtotal; if(member){value-=subtotal/10;} if(value<5000){value+=500;} return value; } }
