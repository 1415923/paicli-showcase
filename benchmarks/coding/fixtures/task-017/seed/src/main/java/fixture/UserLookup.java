package fixture;
import java.util.*;
public final class UserLookup { private final Map<Long,String> users=Map.of(1L,"Ada"); public Optional<String> find(long id){return Optional.of(users.get(id));} }
