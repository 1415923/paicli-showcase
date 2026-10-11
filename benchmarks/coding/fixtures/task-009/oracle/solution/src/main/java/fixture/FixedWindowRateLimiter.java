package fixture;
public final class FixedWindowRateLimiter implements RateLimiter {
    private final int limit;
    private int used;
    public FixedWindowRateLimiter(int limit) { this.limit = limit; }
    public boolean tryAcquire() { if (used >= limit) return false; used++; return true; }
    public int remaining() { return Math.max(0, limit - used); }
}
