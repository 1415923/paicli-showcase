package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class CsvNumbersTest { @Test void sums(){var p=new CsvNumbers();assertEquals(6,p.sum("1, 2,3 "));assertEquals(0,p.sum("   "));assertEquals(-1,p.sum("1,-2"));} }
