package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.safety.ApprovalDecision;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceFixtureApprovalTest {
    @TempDir Path workspace;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String exposed(String tool){return "mcp_chrome_"+tool+"_"+"a".repeat(24);}
    private static com.fasterxml.jackson.databind.JsonNode call(String tool,Map<String,Object> args)throws Exception {
        return JSON.valueToTree(Map.of("function",Map.of("name",tool,"arguments",JSON.writeValueAsString(args))));
    }
    private static List<InvoiceFixtureApproval.Observation> observed(){return List.of(
            new InvoiceFixtureApproval.Observation(exposed("new_page"),"2: D7 invoice filter (http://127.0.0.1:18797/) [selected]"),
            new InvoiceFixtureApproval.Observation(exposed("take_snapshot"),"uid=1_0 RootWebArea \"D7 invoice filter\" url=\"http://127.0.0.1:18797/\"\n  uid=1_3 textbox \"Filter\"\n  uid=1_4 button \"Apply\""));}
    @Test void exactOriginAndParametersOnly()throws Exception {
        String tool=exposed("new_page");
        assertTrue(InvoiceFixtureApproval.allows(tool,call(tool,Map.of("url",InvoiceFixtureApproval.URL)),List.of()));
        for(String url:List.of("https://example.com",InvoiceFixtureApproval.URL+"/admin","http://127.0.0.1:18798",InvoiceFixtureApproval.URL+"@example.com"))
            assertFalse(InvoiceFixtureApproval.allows(tool,call(tool,Map.of("url",url)),List.of()));
        assertFalse(InvoiceFixtureApproval.allows(tool,call(tool,Map.of("url",InvoiceFixtureApproval.URL,"background",true)),List.of()));
    }
    @Test void pageAndElementMustBeObservedOnOwnedFixture()throws Exception {
        String tool=exposed("fill");var args=Map.<String,Object>of("pageId",2,"uid","1_3","value","INV-2047");
        assertTrue(InvoiceFixtureApproval.allows(tool,call(tool,args),observed()));
        assertFalse(InvoiceFixtureApproval.allows(tool,call(tool,args),List.of()));
        for(var wrong:List.of(Map.of("pageId",1,"uid","1_3","value","INV-2047"),
                Map.of("pageId",2,"uid","1_4","value","INV-2047"),Map.of("pageId",2,"uid","1_3","value","secret"),
                Map.of("pageId",2.1,"uid","1_3","value","INV-2047")))
            assertFalse(InvoiceFixtureApproval.allows(tool,call(tool,new HashMap<>(wrong)),observed()));
        String click=exposed("click");
        assertTrue(InvoiceFixtureApproval.allows(click,call(click,Map.of("pageId",2,"uid","1_4")),observed()));
        assertFalse(InvoiceFixtureApproval.allows(click,call(click,Map.of("pageId",2,"uid","1_3")),observed()));
    }
    @Test void readOnlyBrowserOptionsAreTypedAndBounded()throws Exception {
        for(String name:List.of("take_snapshot","list_console_messages","list_network_requests")) {
            String tool=exposed(name);
            assertTrue(InvoiceFixtureApproval.allows(tool,call(tool,Map.of("pageId",2)),observed()));
            assertFalse(InvoiceFixtureApproval.allows(tool,call(tool,Map.of("pageId",3)),observed()));
            assertFalse(InvoiceFixtureApproval.allows(tool,call(tool,Map.of("pageId",2,"arbitrary",true)),observed()));
        }
        String tool=exposed("take_snapshot");
        assertTrue(InvoiceFixtureApproval.allows(tool,call(tool,Map.of("pageId",2,"verbose",true)),observed()));
        assertFalse(InvoiceFixtureApproval.allows(tool,call(tool,Map.of("pageId",2,"verbose","true")),observed()));
    }
    @Test void shellApprovalIsExactCommandNotPrefix()throws Exception {
        String command="curl -s \""+InvoiceFixtureApproval.URL+"/api/risk-check?invoice=INV-2047\"";
        assertTrue(InvoiceFixtureApproval.allows("run_command",call("run_command",Map.of("command",command)),List.of()));
        for(String wrong:List.of(command+" & whoami",command+" > stolen.txt",command.replace("-s","-s -L"),command.replace("18797","18798")))
            assertFalse(InvoiceFixtureApproval.allows("run_command",call("run_command",Map.of("command",wrong)),List.of()));
    }
    @Test void ambiguousBatchMismatchAndUnknownToolFailClosed()throws Exception {
        String tool=exposed("take_snapshot");var c=call(tool,Map.of("pageId",2));
        assertFalse(InvoiceFixtureApproval.allows(tool,JSON.createArrayNode().add(c),observed()));
        assertFalse(InvoiceFixtureApproval.allows(exposed("click"),c,observed()));
        assertFalse(InvoiceFixtureApproval.allows("write_file",call("write_file",Map.of()),observed()));
        assertFalse(InvoiceFixtureApproval.allows(tool,JSON.createObjectNode(),observed()));
    }
    @Test void durableLookupBindsTaskIterationAndTool()throws Exception {
        Path db=workspace.resolve(".paicli/state/paicli.db");Files.createDirectories(db.getParent());
        String tool=exposed("new_page");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()) {
            s.execute("CREATE TABLE events(id INTEGER,task_id TEXT,iteration INTEGER,type TEXT,tool_call_json TEXT)");
            s.execute("CREATE TABLE tool_calls(id INTEGER,task_id TEXT,result_success INTEGER,tool_name TEXT,result_output TEXT)");
            try(var q=c.prepareStatement("INSERT INTO events VALUES(1,'task',7,'ACTION',?)")) {
                q.setString(1,call(tool,Map.of("url",InvoiceFixtureApproval.URL)).toString());q.executeUpdate();
            }
        }
        var request=new InteractiveApprovalRequest("request","session","task","run",7,tool,null,
                ApprovalDecision.RiskLevel.HIGH,"on-request",null,Instant.now().plusSeconds(60));
        assertTrue(InvoiceFixtureApproval.allows(workspace,"task",request));
        assertFalse(InvoiceFixtureApproval.allows(workspace,"another-task",request));
        var wrongIteration=new InteractiveApprovalRequest("request","session","task","run",8,tool,null,
                ApprovalDecision.RiskLevel.HIGH,"on-request",null,Instant.now().plusSeconds(60));
        assertFalse(InvoiceFixtureApproval.allows(workspace,"task",wrongIteration));
    }
}
