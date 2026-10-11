package fixture;
public final class LegacyUser { private final String name; private final String email; public LegacyUser(String name,String email){this.name=name.trim();this.email=email.trim().toLowerCase();} public String name(){return name;} public String email(){return email;} public String display(){return name+" <"+email+">";} }
