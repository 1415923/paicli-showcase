package fixture;
import java.util.*;
public final class EmailIndex { private final Set<String> values=new HashSet<>(); public void add(String email){values.add(TextNormalizer.normalize(email));} public boolean contains(String email){return values.contains(TextNormalizer.normalize(email));} }
