package fixture;
import java.util.Locale;
public final class Slugifier { public String slug(String input) { return input.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$",""); } }
