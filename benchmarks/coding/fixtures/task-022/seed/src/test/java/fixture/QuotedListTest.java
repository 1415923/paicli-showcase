package fixture;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class QuotedListTest { @Test void renders(){var q=new QuotedList();assertEquals("[]",q.render(List.of()));assertEquals("[\"a\",\"b\"]",q.render(List.of("a","b")));assertEquals("[\"a\\\\b\",\"say \\\"hi\\\"\"]",q.render(List.of("a\\b","say \"hi\"")));} }
