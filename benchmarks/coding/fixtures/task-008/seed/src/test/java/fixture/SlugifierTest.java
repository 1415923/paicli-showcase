package fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
class SlugifierTest { @Test void slugifies() { var s=new Slugifier(); assertEquals("hello-world",s.slug("  Hello, World! ")); assertEquals("a-b",s.slug("A___B")); assertEquals("",s.slug("---")); } }
