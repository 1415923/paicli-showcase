package fixture;
public final class RetrySchedule { public long delayMillis(int attempt){if(attempt<1)throw new IllegalArgumentException("attempt");long delay=100L<<Math.min(attempt-1,4);return Math.min(1600,delay);} }
