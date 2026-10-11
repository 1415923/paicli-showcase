package fixture;
import java.util.Optional;
public interface TicketStore { void save(Ticket ticket); Optional<Ticket> find(long id); boolean updateStatus(long id, Ticket.Status status); }
