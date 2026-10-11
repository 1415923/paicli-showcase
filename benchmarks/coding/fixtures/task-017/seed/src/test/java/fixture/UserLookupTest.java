package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class UserLookupTest { @Test void optional(){var lookup=new UserLookup();assertEquals("Ada",lookup.find(1).orElseThrow());assertTrue(lookup.find(2).isEmpty());} }
