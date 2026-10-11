package fixture;
import java.util.Locale;
public record LegacyUser(String name,String email) { public LegacyUser { name=name.trim();email=email.trim().toLowerCase(Locale.ROOT); } public String display(){return name+" <"+email+">";} }
