package fixture;
public final class UserCsvParser { public UserRow parse(String line) { String[] p=line.split(","); return new UserRow(Long.parseLong(p[0]),p[1],p[2],Boolean.parseBoolean(p[3])); } }
