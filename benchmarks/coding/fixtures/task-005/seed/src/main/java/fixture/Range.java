package fixture;
public final class Range { public static int clamp(int value, int min, int max) { return Math.min(min, Math.max(value, max)); } }
