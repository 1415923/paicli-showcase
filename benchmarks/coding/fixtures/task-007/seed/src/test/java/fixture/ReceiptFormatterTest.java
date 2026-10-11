package fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
class ReceiptFormatterTest { @Test void money() { var f=new ReceiptFormatter(); assertEquals("$12.05",f.dollars(1205)); assertEquals("$0.09",f.dollars(9)); assertEquals("-$1.25",f.dollars(-125)); } }
