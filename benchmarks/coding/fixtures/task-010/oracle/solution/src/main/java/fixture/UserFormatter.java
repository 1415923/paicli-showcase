package fixture;
public final class UserFormatter { public String format(UserRow user) { return user.name()+" ["+user.role()+"] "+(user.active()?"active":"inactive"); } }
