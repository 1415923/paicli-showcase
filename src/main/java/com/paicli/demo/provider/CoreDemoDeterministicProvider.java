package com.paicli.demo.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.ToolFactProjection;
import com.paicli.demo.scenario.*;
import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import java.util.*;

public final class CoreDemoDeterministicProvider implements ModelProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String INITIAL_SOURCE="""
            package com.paicli.demo.order;
            import java.math.BigDecimal;
            public class PricingService {
                private static final BigDecimal MEMBER_RATE = new BigDecimal("0.90");
                public BigDecimal subtotal(Order order) {
                    BigDecimal raw = order.lines().stream().map(OrderLine::rawTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return MoneyRules.money(raw);
                }
                public BigDecimal memberTotal(Order order) {
                    return order.lines().stream().map(line -> MoneyRules.money(line.rawTotal().multiply(MEMBER_RATE))).reduce(BigDecimal.ZERO, BigDecimal::add);
                }
            }
            """;
    public static final String FINAL_SOURCE=INITIAL_SOURCE.replace(
            "return order.lines().stream().map(line -> MoneyRules.money(line.rawTotal().multiply(MEMBER_RATE))).reduce(BigDecimal.ZERO, BigDecimal::add);",
            "return MoneyRules.money(subtotal(order).multiply(MEMBER_RATE));");
    public static final String PLAN="""
            1. Inspect PricingService, MoneyRules, Order, OrderLine, and the pricing tests.
            2. Preserve subtotal rounding and empty-order behavior while adding member pricing.
            3. Modify only PricingService.java through the bounded write_file tool.
            4. Run the fixed offline Maven verification.
            5. Use the structured verification failure evidence for one bounded repair.
            """;

    private final CoreDemoScenarioContract contract;
    private final String workspaceHash;
    private State state=State.PLAN;
    private String planHash,codeHash,verifyHash;
    private int step;

    public CoreDemoDeterministicProvider(CoreDemoScenarioContract contract,String workspaceHash){
        this.contract=Objects.requireNonNull(contract);
        if(workspaceHash==null||!workspaceHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid workspace identity");
        this.workspaceHash=workspaceHash;
    }

    public synchronized void armExecution(String expectedPlanHash,String expectedCodeHash){
        if(state!=State.WAIT_EXEC||!sha(expectedPlanHash)||!sha(expectedCodeHash))throw drift("execution arm");
        planHash=expectedPlanHash;codeHash=expectedCodeHash;step=0;state=State.EXEC;
    }
    public synchronized void armRepair(String expectedEvidenceHash){
        if(state!=State.WAIT_REPAIR||!sha(expectedEvidenceHash))throw drift("repair arm");
        verifyHash=expectedEvidenceHash;step=0;state=State.REPAIR;
    }

    public static CoreDemoDeterministicProvider restoreForRepair(
            CoreDemoScenarioContract contract, String workspaceHash,
            com.paicli.state.CoreDemoCheckpointStore.CheckpointRecord checkpoint,
            String currentTreeHash) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!contract.scenarioId().equals("core-java-member-discount-v1")
                || !contract.fixedTaskId().equals(checkpoint.taskId())
                || !contract.expectedInitialImplementationTreeHash().equals(currentTreeHash)
                || !currentTreeHash.equals(checkpoint.initialTreeHash())
                || !contract.providerScriptVersion().equals(checkpoint.providerScriptVersion())
                || !contract.approvalProfileVersion().equals(checkpoint.approvalProfileVersion())
                || !sha(checkpoint.planHash()) || !sha(checkpoint.codeIntelligenceHash())
                || !sha(checkpoint.verificationEvidenceHash())
                || checkpoint.repairAttempt() != 0) {
            throw new ProviderContractException(
                    "DEMO_RESUME_EVIDENCE_DRIFT: checkpoint/provider facts mismatch");
        }
        CoreDemoDeterministicProvider provider =
                new CoreDemoDeterministicProvider(contract, workspaceHash);
        provider.planHash = checkpoint.planHash();
        provider.codeHash = checkpoint.codeIntelligenceHash();
        provider.verifyHash = checkpoint.verificationEvidenceHash();
        provider.step = 0;
        provider.state = State.REPAIR;
        return provider;
    }

    @Override public synchronized ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools){
        return switch(state){
            case PLAN->plan(messages,tools);
            case EXEC->execute(messages,tools);
            case REPAIR->repair(messages,tools);
            default->throw drift("unexpected state "+state);
        };
    }

    private ModelResponse plan(List<AgentMessage> messages,List<ToolDefinition> tools){
        if(!tools.isEmpty()||messages.size()!=1
                ||!messages.getFirst().content().contains(contract.fixedTaskText())
                ||!messages.getFirst().content().contains("Do not call tools"))throw drift("plan input");
        state=State.WAIT_EXEC;return ModelResponse.finalAnswer(PLAN);
    }

    private ModelResponse execute(List<AgentMessage> messages,List<ToolDefinition> tools){
        requireTools(tools);requireIdentityMarkers(messages);marker(messages,"DEMO_PLAN_SHA256="+planHash);
        marker(messages,"DEMO_CODE_INTELLIGENCE_EVIDENCE_SHA256="+codeHash);
        return switch(step++){
            case 0->call("list_files",args("path","src/main/java/com/paicli/demo/order"));
            case 1->{observed(messages,"list_files","PricingService.java");yield call("grep",args(
                    "pattern","subtotal|money|member","path","src/main/java/com/paicli/demo/order"));}
            case 2->{observed(messages,"grep","PricingService.java");yield call("read_file",args(
                    "path",contract.mutableFile(),"max_lines",200));}
            case 3->{observed(messages,"read_file","class PricingService");yield call("write_file",args(
                    "path",contract.mutableFile(),"content",INITIAL_SOURCE,"overwrite",true));}
            case 4->{observed(messages,"write_file","Wrote ");state=State.WAIT_REPAIR;
                yield ModelResponse.finalAnswer("Initial bounded implementation completed.");}
            default->throw drift("execution sequence");
        };
    }

    private ModelResponse repair(List<AgentMessage> messages,List<ToolDefinition> tools){
        requireTools(tools);requireIdentityMarkers(messages);marker(messages,"DEMO_VERIFICATION_EVIDENCE_SHA256="+verifyHash);
        return switch(step++){
            case 0->call("read_file",args("path",contract.mutableFile(),"max_lines",200));
            case 1->{observed(messages,"read_file","line.rawTotal().multiply(MEMBER_RATE)");
                yield call("write_file",args("path",contract.mutableFile(),"content",FINAL_SOURCE,"overwrite",true));}
            case 2->{observed(messages,"write_file","Wrote ");state=State.COMPLETE;
                yield ModelResponse.finalAnswer("Repair applied from structured verification evidence.");}
            default->throw drift("repair sequence");
        };
    }

    private void requireIdentityMarkers(List<AgentMessage> messages){
        marker(messages,"DEMO_SCENARIO_ID="+contract.scenarioId());
        marker(messages,"DEMO_TASK_ID="+contract.fixedTaskId());
        marker(messages,"DEMO_WORKSPACE_IDENTITY_SHA256="+workspaceHash);
    }
    private static void requireTools(List<ToolDefinition> tools){
        Set<String> names=tools.stream().map(ToolDefinition::name).collect(
                java.util.stream.Collectors.toCollection(TreeSet::new));
        if(!names.equals(Set.of("grep","list_files","read_file","write_file")))throw drift("tool registry");
    }
    private static void marker(List<AgentMessage> messages,String marker){
        if(messages.stream().filter(m->m.type()==AgentMessage.MessageType.USER)
                .noneMatch(m->m.content().contains(marker)))throw drift("evidence marker");
    }
    private static void observed(List<AgentMessage> messages,String tool,String text){
        if(messages.size()<2)throw drift("missing observation");
        AgentMessage observation=messages.getLast(),action=messages.get(messages.size()-2);
        if(action.type()!=AgentMessage.MessageType.ACTION
                ||observation.type()!=AgentMessage.MessageType.OBSERVATION)
            throw drift(tool+" observation types");
        if(action.toolCallId()==null||action.toolCallId().isBlank()
                ||!action.toolCallId().equals(observation.toolCallId()))
            throw drift(tool+" observation identity");
        if(!tool.equals(actionToolName(action)))throw drift(tool+" action identity");
        if(observation.content()==null||observation.content().startsWith("ERROR:"))
            throw drift(tool+" observation failed");
        if("list_files".equals(tool)){
            try{
                ToolFactProjection.ControlFacts facts=
                        ToolFactProjection.fromConversation(List.of(action,observation));
                if(facts==null||facts.visibleEntries().stream().noneMatch(text::equals))
                    throw drift("list_files canonical fact");
            }catch(ToolFactProjection.FactIntegrityFailure failure){
                throw drift("list_files canonical fact integrity");
            }
        }else if(!observation.content().contains(text)){
            throw drift(tool+" observation content");
        }
    }
    private static String actionToolName(AgentMessage action){
        if(action.toolCallJson()==null||action.toolCallJson().isBlank())
            throw drift("action identity missing");
        try{
            JsonNode call=JSON.readTree(action.toolCallJson());
            if(call==null||!action.toolCallId().equals(call.path("id").asText()))
                throw drift("action identity mismatch");
            String name=call.path("function").path("name").asText();
            if(name.isBlank())throw drift("action tool missing");
            return name;
        }catch(ProviderContractException failure){
            throw failure;
        }catch(Exception invalid){
            throw drift("action identity malformed");
        }
    }
    private static ModelResponse call(String name,Map<String,Object> args){
        return ModelResponse.toolCallResponse("bounded demo action",new ToolCall(name,args));
    }
    private static Map<String,Object> args(Object... values){
        LinkedHashMap<String,Object> map=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1]);
        return Collections.unmodifiableMap(map);
    }
    public static String planHash(){return CoreDemoContractHash.sha256(PLAN);}
    private static boolean sha(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static ProviderContractException drift(String fact){
        return new ProviderContractException("DEMO_PROVIDER_CONTRACT_DRIFT: "+fact);
    }
    private enum State{PLAN,WAIT_EXEC,EXEC,WAIT_REPAIR,REPAIR,COMPLETE}
    public static final class ProviderContractException extends RuntimeException{
        ProviderContractException(String message){super(message);}
    }
}
