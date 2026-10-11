package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ExpiringValueTest { @Test void boundaries(){var v=new ExpiringValue(100,20);assertFalse(v.isExpired(119));assertTrue(v.isExpired(120));assertThrows(IllegalArgumentException.class,()->new ExpiringValue(0,-1));var max=new ExpiringValue(Long.MAX_VALUE-2,10);assertFalse(max.isExpired(Long.MAX_VALUE-1));} }
