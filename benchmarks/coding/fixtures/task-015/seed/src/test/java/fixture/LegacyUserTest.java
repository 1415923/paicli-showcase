package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class LegacyUserTest { @Test void behavior(){var u=new LegacyUser(" Ada "," ADA@EXAMPLE.COM ");assertEquals("Ada",u.name());assertEquals("ada@example.com",u.email());assertEquals("Ada <ada@example.com>",u.display());} }
