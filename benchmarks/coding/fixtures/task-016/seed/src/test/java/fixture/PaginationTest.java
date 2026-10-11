package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PaginationTest { @Test void counts(){var p=new Pagination();assertEquals(0,p.pageCount(0,10));assertEquals(1,p.pageCount(1,10));assertEquals(1,p.pageCount(10,10));assertEquals(2,p.pageCount(11,10));assertEquals(2,p.pageCount(20,10));} }
