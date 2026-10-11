package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class TicketStoreTest { @Test void statusUpdates() { TicketStore s=new InMemoryTicketStore(); s.save(new Ticket(1,"broken",Ticket.Status.OPEN)); assertTrue(s.updateStatus(1,Ticket.Status.CLOSED)); assertEquals(Ticket.Status.CLOSED,s.find(1).orElseThrow().status()); assertFalse(s.updateStatus(9,Ticket.Status.CLOSED)); } }
