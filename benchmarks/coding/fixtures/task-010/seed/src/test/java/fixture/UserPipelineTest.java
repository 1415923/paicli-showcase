package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class UserPipelineTest { @Test void activeColumnFlowsThrough() { UserRow row=new UserCsvParser().parse("7,Ada,admin,true"); assertTrue(row.active()); assertEquals("Ada [admin] active",new UserFormatter().format(row)); } }
