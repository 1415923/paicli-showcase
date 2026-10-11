package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class CounterTest { @Test void bothDirections(){var c=new Counter(4);assertEquals(5,c.increment().value());assertEquals(3,c.decrement().value());assertEquals(4,c.value());} }
