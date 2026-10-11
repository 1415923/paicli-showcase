package fixture;
public final class Range { public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); } }
