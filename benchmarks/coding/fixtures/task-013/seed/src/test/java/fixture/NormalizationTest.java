package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class NormalizationTest { @Test void behavior() { assertTrue(new CustomerMatcher().same(" Ada ","ADA")); EmailIndex i=new EmailIndex(); i.add(" A@EXAMPLE.COM "); assertTrue(i.contains("a@example.com")); } }
