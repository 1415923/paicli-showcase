package fixture;
public final class ExpiringValue { private final long deadline; public ExpiringValue(long created,long ttl){deadline=created+ttl;} public boolean isExpired(long now){return now>deadline;} }
