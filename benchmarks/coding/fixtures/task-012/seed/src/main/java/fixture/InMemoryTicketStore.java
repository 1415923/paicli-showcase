package fixture;
import java.util.*;
public final class InMemoryTicketStore implements TicketStore { private final Map<Long,Ticket> tickets=new HashMap<>(); public void save(Ticket t){tickets.put(t.id(),t);} public Optional<Ticket> find(long id){return Optional.ofNullable(tickets.get(id));} }
