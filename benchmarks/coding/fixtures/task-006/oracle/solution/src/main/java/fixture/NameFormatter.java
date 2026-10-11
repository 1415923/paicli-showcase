package fixture;
public final class NameFormatter { public String display(String name) { return name == null || name.isBlank() ? "Anonymous" : name.trim(); } }
