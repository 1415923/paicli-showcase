package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class RateLimiterTest { @Test void remainingTracksUsage() { RateLimiter r=new FixedWindowRateLimiter(2); assertEquals(2,r.remaining()); assertTrue(r.tryAcquire()); assertEquals(1,r.remaining()); assertTrue(r.tryAcquire()); assertFalse(r.tryAcquire()); assertEquals(0,r.remaining()); } }
