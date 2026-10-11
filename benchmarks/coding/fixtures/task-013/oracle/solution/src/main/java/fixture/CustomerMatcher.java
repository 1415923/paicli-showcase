package fixture;
public final class CustomerMatcher { public boolean same(String a,String b){return TextNormalizer.normalize(a).equals(TextNormalizer.normalize(b));} }
