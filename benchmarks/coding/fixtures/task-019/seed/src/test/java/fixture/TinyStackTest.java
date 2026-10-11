package fixture;
import static org.junit.jupiter.api.Assertions.*;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
class TinyStackTest { @Test void lifo(){var s=new TinyStack<String>();s.push("a");s.push("b");assertEquals("b",s.pop());assertEquals("a",s.pop());assertThrows(NoSuchElementException.class,s::pop);} }
