package fixture;
public record Ticket(long id, String title, Status status) { public enum Status { OPEN, CLOSED } }
