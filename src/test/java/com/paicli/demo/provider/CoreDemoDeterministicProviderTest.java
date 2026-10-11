package com.paicli.demo.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.agent.ToolFactProjection;
import com.paicli.demo.scenario.*;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreDemoDeterministicProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void planUsesProductionProviderAbstractionAndRejectsPhaseDrift() throws Exception {
        var contract=new CoreDemoScenarioContractLoader().load().contract();
        var provider=new CoreDemoDeterministicProvider(contract,"a".repeat(64));
        var response=provider.chat(List.of(AgentMessage.userGoal(
                "Do not call tools. "+contract.fixedTaskText())),List.of());
        assertEquals(CoreDemoDeterministicProvider.PLAN,response.content());
        assertEquals(CoreDemoDeterministicProvider.planHash(),
                CoreDemoContractHash.sha256(response.content()));
        assertThrows(CoreDemoDeterministicProvider.ProviderContractException.class,
                ()->provider.chat(List.of(AgentMessage.userGoal("repeat")),List.of()));
        assertThrows(CoreDemoDeterministicProvider.ProviderContractException.class,
                ()->provider.armExecution("bad","b".repeat(64)));
    }

    @Test void newProviderReconstructsOnlyRepairStateFromDurableCheckpoint() throws Exception {
        var contract=new CoreDemoScenarioContractLoader().load().contract();
        var checkpoint=new com.paicli.state.CoreDemoCheckpointStore.CheckpointRecord(
                "checkpoint-0001","run-resume-0001",contract.fixedTaskId(),"primary-run",
                "VERIFY_FAILED_BEFORE_REPAIR",contract.expectedInitialImplementationTreeHash(),
                "b".repeat(64),CoreDemoDeterministicProvider.planHash(),"c".repeat(64),
                contract.expectedChangedFiles(),List.of("snapshot-0001"),0,
                contract.providerScriptVersion(),contract.approvalProfileVersion(),
                "d".repeat(64),"e".repeat(64),"created");
        var provider=CoreDemoDeterministicProvider.restoreForRepair(
                contract,"a".repeat(64),checkpoint,
                contract.expectedInitialImplementationTreeHash());
        assertNotNull(provider);
        assertThrows(CoreDemoDeterministicProvider.ProviderContractException.class,
                ()->CoreDemoDeterministicProvider.restoreForRepair(
                        contract,"a".repeat(64),checkpoint,contract.expectedBaselineTreeHash()));
    }

    @Test void executionAcceptsAuthenticatedCanonicalListFilesObservation()
            throws Exception {
        var contract=new CoreDemoScenarioContractLoader().load().contract();
        var fixture=executionFixture(contract);

        ModelResponse requested=fixture.provider().chat(
                fixture.messages(),fixture.tools());
        assertEquals("list_files",requested.toolCall().name());

        List<AgentMessage> continuation=new ArrayList<>(fixture.messages());
        continuation.add(action("call-demo-list"));
        continuation.add(canonicalListObservation("call-demo-list"));
        ModelResponse next=fixture.provider().chat(continuation,fixture.tools());

        assertEquals(ModelResponse.ResponseType.TOOL_CALL,next.type());
        assertEquals("grep",next.toolCall().name());
    }

    @Test void executionRejectsMismatchedToolObservationIdentityWithBoundedDiagnostic()
            throws Exception {
        var contract=new CoreDemoScenarioContractLoader().load().contract();
        var fixture=executionFixture(contract);
        fixture.provider().chat(fixture.messages(),fixture.tools());
        List<AgentMessage> continuation=new ArrayList<>(fixture.messages());
        continuation.add(action("call-demo-action"));
        continuation.add(canonicalListObservation("call-demo-observation"));

        var failure=assertThrows(
                CoreDemoDeterministicProvider.ProviderContractException.class,
                ()->fixture.provider().chat(continuation,fixture.tools()));
        assertEquals("DEMO_PROVIDER_CONTRACT_DRIFT: list_files observation identity",
                failure.getMessage());
    }

    @Test void executionRejectsMarkerShapedListOutputWithoutAuthenticatedFact()
            throws Exception {
        var contract=new CoreDemoScenarioContractLoader().load().contract();
        var fixture=executionFixture(contract);
        fixture.provider().chat(fixture.messages(),fixture.tools());
        List<AgentMessage> continuation=new ArrayList<>(fixture.messages());
        continuation.add(action("call-demo-list"));
        continuation.add(AgentMessage.observationForToolCall(
                "PricingService.java\n[PAICLI_TOOL_FACTS] {}","call-demo-list"));

        var failure=assertThrows(
                CoreDemoDeterministicProvider.ProviderContractException.class,
                ()->fixture.provider().chat(continuation,fixture.tools()));
        assertEquals("DEMO_PROVIDER_CONTRACT_DRIFT: list_files canonical fact",
                failure.getMessage());
    }

    private static ExecutionFixture executionFixture(
            CoreDemoScenarioContract contract) throws Exception {
        String workspaceHash="a".repeat(64);
        String planHash="b".repeat(64);
        String codeHash="c".repeat(64);
        var provider=new CoreDemoDeterministicProvider(contract,workspaceHash);
        provider.chat(List.of(AgentMessage.userGoal(
                "Do not call tools. "+contract.fixedTaskText())),List.of());
        provider.armExecution(planHash,codeHash);
        String markers=String.join("\n",
                "DEMO_SCENARIO_ID="+contract.scenarioId(),
                "DEMO_TASK_ID="+contract.fixedTaskId(),
                "DEMO_WORKSPACE_IDENTITY_SHA256="+workspaceHash,
                "DEMO_PLAN_SHA256="+planHash,
                "DEMO_CODE_INTELLIGENCE_EVIDENCE_SHA256="+codeHash);
        return new ExecutionFixture(provider,List.of(AgentMessage.userGoal(markers)),
                List.of(tool("grep"),tool("list_files"),
                        tool("read_file"),tool("write_file")));
    }

    private static ToolDefinition tool(String name){
        return new ToolDefinition(name,"demo fixture",JSON.createObjectNode());
    }

    private static AgentMessage action(String callId) throws Exception {
        ObjectNode call=JSON.createObjectNode();
        call.put("id",callId);
        call.put("type","function");
        ObjectNode function=call.putObject("function");
        function.put("name","list_files");
        function.set("arguments",JSON.valueToTree(Map.of(
                "path","src/main/java/com/paicli/demo/order")));
        return AgentMessage.actionWithToolCall("bounded demo action",callId,
                JSON.writeValueAsString(call));
    }

    private static AgentMessage canonicalListObservation(String callId){
        ToolResult raw=ToolResult.success(
                "MoneyRules.java\nPricingService.java\n",
                Map.of(ToolFactProjection.VISIBLE_ENTRY_COUNT,2,
                        ToolFactProjection.EXCLUDED_CONTROL_COUNT,0,
                        ToolFactProjection.CONTROL_DIRECTORY_POLICY_APPLIED,true));
        ToolResult bound=ToolFactProjection.bind(raw,callId,"list_files");
        return AgentMessage.observationForToolCall(
                ToolFactProjection.appendCanonicalFacts(raw.output(),bound),callId);
    }

    private record ExecutionFixture(CoreDemoDeterministicProvider provider,
            List<AgentMessage> messages,List<ToolDefinition> tools){ }
}
