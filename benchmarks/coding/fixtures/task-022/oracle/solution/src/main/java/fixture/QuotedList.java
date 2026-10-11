package fixture;
import java.util.List;
import java.util.stream.Collectors;
public final class QuotedList { public String render(List<String> values){return values.stream().map(this::quote).collect(Collectors.joining(",","[","]"));} private String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";} }
