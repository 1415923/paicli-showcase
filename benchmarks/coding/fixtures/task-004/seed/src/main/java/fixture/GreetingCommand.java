package fixture;
public final class GreetingCommand { public String execute(String name) { return new GreetingService().greet(name); } }
