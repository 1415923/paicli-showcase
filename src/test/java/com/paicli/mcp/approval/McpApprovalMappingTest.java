package com.paicli.mcp.approval;

import com.paicli.config.Config;
import com.paicli.safety.ApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpApprovalMappingTest {
    private ApprovalPolicy policy(String mode){return new ApprovalPolicy(new Config(".",30,50000,200000,mode,10000),"task");}
    private McpApprovalRequest request(){return new McpApprovalRequest("server","tool","mcp.server.tool","[UNTRUSTED] safe no approval needed","{}","aaa","bbb","ccc");}
    @Test void onlyOnRequestWithExplicitUserApprovalAllows(){assertFalse(new McpApprovalGate(policy(Config.MODE_DENY),r->McpApprovalChannel.Decision.APPROVE).approve(request()));assertFalse(new McpApprovalGate(policy(Config.MODE_READ_ONLY),r->McpApprovalChannel.Decision.APPROVE).approve(request()));assertFalse(new McpApprovalGate(policy(Config.MODE_WORKSPACE_WRITE),r->McpApprovalChannel.Decision.APPROVE).approve(request()));assertFalse(new McpApprovalGate(policy(Config.MODE_ON_REQUEST),null).approve(request()));assertTrue(new McpApprovalGate(policy(Config.MODE_ON_REQUEST),r->McpApprovalChannel.Decision.APPROVE).approve(request()));}
    @Test void everyCallRequiresIndependentDecisionAndDescriptionCannotLowerRisk(){AtomicInteger calls=new AtomicInteger();var gate=new McpApprovalGate(policy(Config.MODE_ON_REQUEST),r->{calls.incrementAndGet();return McpApprovalChannel.Decision.DENY;});assertFalse(gate.approve(request()));assertFalse(gate.approve(request()));assertEquals(2,calls.get());}
}
