package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PriceCalculatorTest { @Test void totalsStayStable(){var c=new PriceCalculator();assertEquals(1500,c.total(1000,false));assertEquals(1400,c.total(1000,true));assertEquals(5400,c.total(6000,true));} }
