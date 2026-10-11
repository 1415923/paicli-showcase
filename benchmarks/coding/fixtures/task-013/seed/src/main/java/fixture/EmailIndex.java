package fixture;
import java.util.*;
public final class EmailIndex { private final Set<String> values=new HashSet<>(); public void add(String email){values.add(email.trim().toLowerCase(Locale.ROOT));} public boolean contains(String email){return values.contains(email.trim().toLowerCase(Locale.ROOT));} }
