package fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
class RangeTest { @Test void clamps() { assertEquals(1, Range.clamp(-2,1,5)); assertEquals(3, Range.clamp(3,1,5)); assertEquals(5, Range.clamp(9,1,5)); } }
