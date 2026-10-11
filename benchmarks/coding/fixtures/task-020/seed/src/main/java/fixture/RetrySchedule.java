package fixture;
public final class RetrySchedule { public long delayMillis(int attempt){return Math.min(1600,100L*attempt);} }
