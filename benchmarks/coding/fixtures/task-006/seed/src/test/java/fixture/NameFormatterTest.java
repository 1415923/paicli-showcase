package fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
class NameFormatterTest { @Test void formats() { var f=new NameFormatter(); assertEquals("Anonymous",f.display(null)); assertEquals("Anonymous",f.display("  ")); assertEquals("Ada",f.display(" Ada ")); } }
