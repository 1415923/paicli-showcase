package fixture;
public interface RateLimiter { boolean tryAcquire(); int remaining(); }
