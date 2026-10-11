package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class RetryScheduleTest { @Test void exponentialAndCapped(){var r=new RetrySchedule();assertEquals(100,r.delayMillis(1));assertEquals(200,r.delayMillis(2));assertEquals(400,r.delayMillis(3));assertEquals(800,r.delayMillis(4));assertEquals(1600,r.delayMillis(5));assertEquals(1600,r.delayMillis(8));} }
