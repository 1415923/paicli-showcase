package fixture;
import java.util.Locale;
public final class TextNormalizer { private TextNormalizer() {} public static String normalize(String value){return value.trim().toLowerCase(Locale.ROOT);} }
