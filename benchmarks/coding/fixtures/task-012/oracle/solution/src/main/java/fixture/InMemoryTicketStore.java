package fixture;
import java.util.*;
public final class InMemoryTicketStore implements TicketStore { private final Map<Long,Ticket> tickets=new HashMap<>(); public void save(Ticket t){tickets.put(t.id(),t);} public Optional<Ticket> find(long id){return Optional.ofNullable(tickets.get(id));} public boolean updateStatus(long id,Ticket.Status status){Ticket old=tickets.get(id);if(old==null)return false;tickets.put(id,new Ticket(old.id(),old.title(),status));return true;} }
