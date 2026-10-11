package fixture;
public final class ExpiringValue { private final long deadline; public ExpiringValue(long created,long ttl){if(ttl<0)throw new IllegalArgumentException("ttl");long value;try{value=Math.addExact(created,ttl);}catch(ArithmeticException ignored){value=Long.MAX_VALUE;}deadline=value;} public boolean isExpired(long now){return now>=deadline;} }
