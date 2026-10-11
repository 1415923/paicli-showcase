package fixture;
import java.util.Locale;
public final class CustomerMatcher { public boolean same(String a,String b){return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));} }
