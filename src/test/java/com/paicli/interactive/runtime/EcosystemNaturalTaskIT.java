package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.*;
import com.paicli.model.*;
import com.paicli.provider.*;
import com.paicli.provider.telemetry.*;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.skill.SkillPackageFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit diagnostic. Preflight uses a scripted Provider; paid mode requires a separate authorization. */
@EnabledIfSystemProperty(named="paicli.natural.mode",matches="preflight|paid")
class EcosystemNaturalTaskIT {
    static InteractiveSession configuredSession(String id,Path work,Config config){
        var verification=com.paicli.interactive.verification.InteractiveVerificationPolicy.fromConfig(config);
        return new InteractiveSession(id,work,config.effectiveProvider(),config.model(),
                Config.MODE_ON_REQUEST,InteractiveSession.TerminalMode.PLAIN,Instant.now(),
                verification.mode(),verification.preset(),"config");
    }
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final String OLD_DESCRIPTION="Execute a shell command in the workspace. Requires high-risk approval.";

    /** Scripted fixture only: exercise the same admission path without a paid provider. */
    static ModelProvider managedPreflight(ModelProvider scripted,Path control) {
        return managedPreflight(scripted,control,null);
    }
    static ModelProvider managedPreflight(ModelProvider scripted,Path control,OpenAICompatibleProvider nativeEncoder) {
        return new ModelProvider() {
            int preparedTurns;
            @Override public OutputRequirement.Mode managedOutputMode(){return nativeEncoder==null
                    ?OutputRequirement.Mode.PROMPT_ONLY:OutputRequirement.Mode.DIAGNOSTIC_NATIVE_CANDIDATE;}
            @Override public boolean usesManagedAnswerEnvelope(){return true;}
                @Override public void validateOutputRequirement(com.paicli.provider.OutputRequirement requirement,boolean hasTools){
                    if(nativeEncoder!=null)nativeEncoder.validateOutputRequirement(requirement,hasTools);
                    else if(requirement.nativeJson())throw new IllegalArgumentException("FIXTURE_PROMPT_ONLY");
                }
                @Override public com.paicli.model.ModelResponse chatForOperation(String operation,List<com.paicli.model.AgentMessage> messages,
                        List<com.paicli.model.ToolDefinition> tools,com.paicli.provider.OutputRequirement requirement){
                    validateOutputRequirement(requirement,!tools.isEmpty());
                    if(nativeEncoder!=null){
                        var prepared=nativeEncoder.prepareTurn(operation,messages,tools,requirement);
                        writeUnchecked(control.resolve(String.format(Locale.ROOT,"preflight-native-%04d.json",++preparedTurns)),
                                Map.of("outputRequirement",requirement.identity(),"metrics",prepared.metrics(),
                                        "requestDigest",prepared.requestDigest(),"definitions",tools,"messages",messages,"realHttpAttempts",0));
                    }
                    return chatForOperation(operation,messages,tools);
                }
            @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> definitions) {
                assertTrue(messages.stream().anyMatch(m->Objects.toString(m.content(),"")
                        .contains(com.paicli.context.ManagedAnswerContract.INSTRUCTION)));
                var response=scripted.chat(messages,definitions);
                if(response.type()!=ModelResponse.ResponseType.FINAL)return response;
                try {
                    String encoded=JSON.writeValueAsString(Map.of("userAnswer",response.content()));
                    assertEquals(response.content(),com.paicli.context.ManagedAnswerContract.decode(encoded));
                    writeUnchecked(control.resolve("preflight-answer-contract.json"),Map.of(
                            "contract",com.paicli.context.ManagedAnswerContract.VERSION,
                            "compiledInstructionPresent",true,"encodedFinal",encoded,"realHttpAttempts",0));
                    return ModelResponse.finalAnswer(encoded);
                } catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
            }
        };
    }

    static List<ToolDefinition> treatment(List<ToolDefinition> definitions,String arm){
        if(!Set.of("before","after").contains(arm))throw new IllegalArgumentException("Unknown treatment");
        return definitions.stream().map(d->arm.equals("before")&&d.name().equals("run_command")
                ?new ToolDefinition(d.name(),OLD_DESCRIPTION,d.parameterSchema()):d).toList();
    }

    static com.fasterxml.jackson.databind.JsonNode requireP3Qualification(com.fasterxml.jackson.databind.JsonNode manifest,Path manifestPath,String caseId)throws Exception {
        String prerequisite="N1".equals(caseId)?"Q1":"Q3";
        com.fasterxml.jackson.databind.JsonNode selected=null;
        for(String required:caseId.startsWith("N")?List.of("Q3","Q1"):List.of("Q3")){
            var row=java.util.stream.StreamSupport.stream(manifest.path("rows").spliterator(),false)
                    .filter(r->required.equals(r.path("case").asText())).findFirst().orElseThrow();
            var qualification=JSON.readTree(Files.readString(Path.of(row.path("paidControl").asText()).resolve("qualification.json")));
            assertEquals("QUALIFIED",qualification.path("status").asText());
            assertEquals(hash(manifestPath),qualification.path("manifestSha256").asText());
            if(prerequisite.equals(required))selected=qualification;
        }
        return Objects.requireNonNull(selected);
    }

    static void requireCatalog(String expected,com.fasterxml.jackson.databind.JsonNode definitions){
        try{assertEquals(expected,SkillPackageFiles.hash(JSON.writeValueAsBytes(definitions)),"P3_TOOL_CATALOG_DRIFT");}
        catch(java.io.IOException error){throw new IllegalStateException(error);}
    }

    static void requireQualificationRead(com.paicli.state.ToolCallStore.ToolCallRecord call,
            com.fasterxml.jackson.databind.JsonNode continuation)throws Exception {
        assertEquals("read_file",call.toolName());
        assertEquals("qualification.txt",JSON.readTree(call.argumentsJson()).path("path").asText());
        assertTrue(call.success(),"Qualification read must succeed");
        assertTrue(Objects.toString(call.output(),"").contains("P3-QUAL-628491"));
        assertTrue(java.util.stream.StreamSupport.stream(continuation.path("messages").spliterator(),false)
                .anyMatch(message->"OBSERVATION".equals(message.path("type").asText())
                        &&call.toolCallId().equals(message.path("toolCallId").asText())
                        &&message.path("content").asText().contains("P3-QUAL-628491")),
                "Successful read must reach the matching provider continuation");
    }

    @Test void executeOneFrozenRow()throws Exception {
        Path manifestPath=Path.of(System.getProperty("paicli.natural.manifest"));
        var manifest=JSON.readTree(Files.readString(manifestPath));
        for(var entry:manifest.path("frozenFiles"))assertEquals(entry.path("sha256").asText(),
                hash(Path.of(entry.path("path").asText())),"Frozen file drift: "+entry.path("path"));
        String id=System.getProperty("paicli.natural.row");
        var row=java.util.stream.StreamSupport.stream(manifest.path("rows").spliterator(),false)
                .filter(r->r.path("id").asText().equals(id)).findFirst().orElseThrow();
        boolean paid=System.getProperty("paicli.natural.mode").equals("paid");
        boolean p3="af-p3-v1".equals(manifest.path("kind").asText());
        Path control=Path.of(row.path(paid?"paidControl":"preflightControl").asText());
        Path work=Path.of(row.path(paid?"paidWorkspace":"preflightWorkspace").asText());
        Path campaign=Path.of(manifest.path("paidCampaign").asText());
        assertFalse(Files.exists(control.resolve("started.json")),"No row replacement");
        Config config=Config.defaults().merge(JSON.treeToValue(manifest.path("config"),Config.class));
        var credential=ProviderCredentialResolver.systemDefault().status(config);
        if(paid){
            var authorization=JSON.readTree(Files.readString(Path.of(System.getProperty("paicli.natural.authorization"))));
            assertEquals(hash(manifestPath),authorization.path("manifestSha256").asText());
            assertEquals(authorizedTaskCount(manifest),authorization.path("maximumTasks").asInt());
            assertEquals(DeliveryRecheckLimits.budget(manifest),authorization.path("budgetCny").asText());
            if(p3){
                assertEquals("DIAGNOSTIC_NATIVE_CANDIDATE",manifest.path("outputMode").asText());
                assertEquals(manifest.path("outputMode").asText(),authorization.path("outputMode").asText());
                if(!row.path("case").asText().equals("Q3")){
                    requireP3Qualification(manifest,manifestPath,row.path("case").asText());
                }
            }
            if(row.has("approvalResponder")) {
                assertEquals("N1",row.path("case").asText());
                assertEquals(InvoiceFixtureApproval.POLICY,row.path("approvalResponder").asText());
                assertEquals(InvoiceFixtureApproval.POLICY,authorization.path("approvalResponder").asText());
            }
            assertTrue(credential.present(),"Credential required before task admission");
        }
        Files.createDirectories(control);Files.createDirectories(campaign);
        try(var channel=FileChannel.open(campaign.resolve("campaign.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.tryLock()){
            assertNotNull(lock,"Another campaign row is active");
            write(control.resolve("started.json"),Map.of("id",id,"mode",paid?"paid":"preflight",
                    "manifestSha256",hash(manifestPath),"credentialPresent",credential.present(),"startedAt",Instant.now().toString()));
            var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("status","INTERRUPTED");
            result.put("intent","DIAGNOSTIC");result.put("mode",paid?"paid":"preflight");result.put("workspace",work.toString());
            var budget=new EcosystemD7SmokeIT.Budget(campaign.resolve("budget-attempts.jsonl"),new BigDecimal(DeliveryRecheckLimits.budget(manifest)));
            ModelProvider provider;
            CapturingProvider capturing=null;
            if(paid){
                var bounded=DeliveryRecheckLimits.provider((OpenAICompatibleProvider)ProviderFactory.create(config),budget,id,manifest,row);
                capturing=new CapturingProvider(bounded,control,row.path("arm").asText());
                if(p3&&!row.path("case").asText().startsWith("Q"))capturing.requiredCatalogHash=
                        requireP3Qualification(manifest,manifestPath,row.path("case").asText()).path("toolDefinitionsSha256").asText();
                capturing.captureDecodedResponses=p3||"quality-recheck-v1".equals(manifest.path("kind").asText());provider=capturing;
            }else if(row.path("approvalResponder").asText().equals(InvoiceFixtureApproval.POLICY)) {
                assertEquals("N1",row.path("case").asText());
                provider=new InvoiceFixtureScenario();
            }else if(p3&&row.path("case").asText().startsWith("Q")){
                provider=(messages,definitions)->messages.stream().anyMatch(m->m.type()==AgentMessage.MessageType.OBSERVATION
                        &&Objects.toString(m.content(),"").contains("P3-QUAL-628491"))
                        ?ModelResponse.finalAnswer("P3-QUAL-628491")
                        :ModelResponse.toolCallResponse("Read qualification file",new ToolCall(
                                "read_file",Map.of("path","qualification.txt"),"p3-qualification-read"));
            }else{
                provider=(messages,definitions)->{
                    writeUnchecked(control.resolve("preflight-provider-input.json"),Map.of("messages",messages,
                            "definitions",treatment(definitions,row.path("arm").asText()),"realHttpAttempts",0));
                    assertTrue(definitions.stream().anyMatch(d->d.name().equals("load_skill")));
                    assertTrue(messages.stream().anyMatch(m->Objects.toString(m.content(),"").contains("untrusted_skill_catalog")));
                    if(!row.path("server").asText().isEmpty())assertTrue(definitions.stream().anyMatch(d->d.name().startsWith("mcp_")));
                    return ModelResponse.finalAnswer("Preflight inspected the available task context; no task result is claimed.");
                };
            }
            ModelProvider taskProvider=provider;
            if(manifest.has("answerContract")) {
                assertEquals(com.paicli.context.ManagedAnswerContract.VERSION,manifest.path("answerContract").asText());
                if(paid) assertTrue(provider.usesManagedAnswerEnvelope(),"Paid entry must preserve the managed contract");
                else taskProvider=managedPreflight(provider,control,p3?new OpenAICompatibleProvider(
                        config.baseUrl(),"preflight-never-sent",config.model(),config.effectiveSystemPrompt(),5,
                        OpenAICompatiblePreset.from(config),config.effectiveThinking(),config.effectiveReasoningEffort()):null);
            }
            ModelProvider selectedProvider=taskProvider;
            Config taskConfig=paid&&row.path("case").asText().equals("N2")?config.withVerification("verify.cmd",true):config;
            try(var runtime=InteractiveRuntimeAdapter.open(work,
                    new ProductionInteractiveTaskExecutor(work,()->taskConfig,ignored->selectedProvider),event->{})){
                var session=configuredSession("natural-"+id,work,taskConfig);
                session.start();runtime.openSession(session);
                for(var source:manifest.path("skills")){
                    Path path=Path.of(source.asText());
                    assertEquals("SKILLS",runtime.skillCommand(List.of("import",path.toString(),SkillPackageFiles.snapshot(path).hash()),session).code());
                }
                String server=row.path("server").asText();
                if(!server.isEmpty()){
                    String configPath=row.path(paid?"paidMcpConfig":"preflightMcpConfig").asText();
                    var imported=runtime.mcpCommand(List.of("config","import",configPath),session);
                    write(control.resolve("mcp-import-result.json"),imported);
                    assertEquals("MCP",imported.code(),imported.toString());
                    var preview=runtime.mcpCommand(List.of("connect",server),session);
                    assertEquals("MCP",preview.code(),preview.toString());
                    String confirmation=preview.lines().getLast().replaceFirst("^.* ","");
                    assertTrue(confirmation.matches("[a-f0-9]{64}"));
                    var connected=runtime.mcpCommand(List.of("connect",server,confirmation),session);
                    assertEquals("MCP",connected.code(),connected.toString());
                }
                runtime.submit(new InteractiveTaskRequest("natural-"+id,work,row.path("goal").asText(),List.of()));
                var task=runtime.activeForegroundTask().orElseThrow();
                write(control.resolve("task.json"),Map.of("taskId",task.taskId(),"workspace",work.toString()));
                result.put("taskId",task.taskId());
                // Outer deadline includes Maven/startup; reserve time for cancellation and durable close.
                long remainingMillis=Long.getLong("paicli.natural.deadlineEpochMillis",
                        System.currentTimeMillis()+TimeUnit.MINUTES.toMillis(paid?20:2))-System.currentTimeMillis();
                long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,remainingMillis-40_000));
                boolean fixtureResponder=row.path("approvalResponder").asText().equals(InvoiceFixtureApproval.POLICY);
                var terminal=awaitTask(runtime,task,work,control,deadline,fixtureResponder,result);
                if(!paid)assertEquals(TaskSubmissionResult.Status.COMPLETED,terminal.status());
                if(!server.isEmpty())assertEquals("MCP",runtime.mcpCommand(List.of("disconnect"),session).code());
                if(!paid&&p3&&row.path("case").asText().startsWith("Q")){
                    try(var state=new com.paicli.state.StateStore(work)){
                        var calls=state.toolCalls.findByTask(task.taskId());assertEquals(1,calls.size());
                        requireQualificationRead(calls.getFirst(),JSON.readTree(Files.readString(control.resolve("preflight-native-0002.json"))));
                    }
                    write(control.resolve("preflight-qualification.json"),Map.of("status","LOCAL_CHAIN_PASSED",
                            "realHttpAttempts",0,"preparedTurns",2));
                }
                if(paid&&p3&&row.path("case").asText().startsWith("Q")){
                    assertEquals(TaskSubmissionResult.Status.COMPLETED,terminal.status());
                    assertEquals(2,capturing.delegate.attempts);
                    assertFalse(budget.unknown);assertTrue(budget.reservations.isEmpty());
                    try(var state=new com.paicli.state.StateStore(work)){
                        var calls=state.toolCalls.findByTask(task.taskId());
                        assertEquals(1,calls.size());assertEquals("read_file",calls.getFirst().toolName());
                        requireQualificationRead(calls.getFirst(),JSON.readTree(Files.readString(control.resolve("prepared-0002.json"))));
                        var run=state.codingRuns.findByTask(task.taskId()).getFirst();
                        var answer=state.userResults.find(com.paicli.identity.CanonicalId.parse(task.taskId()),
                                com.paicli.identity.CanonicalId.parse(run.runId())).orElseThrow();
                        assertEquals("P3-QUAL-628491",answer.content());
                    }
                    var first=JSON.readTree(Files.readString(control.resolve("prepared-0001.json")));
                    var last=JSON.readTree(Files.readString(control.resolve("prepared-0002.json")));
                    assertEquals("OUTPUT_REQUIREMENT_V1:PAICLI_USER_ANSWER_V1:DIAGNOSTIC_NATIVE_CANDIDATE",
                            first.path("outputRequirement").asText());
                    assertEquals(first.path("outputRequirement"),last.path("outputRequirement"));
                    assertEquals(first.path("definitions"),last.path("definitions"));
                    write(control.resolve("qualification.json"),Map.of("status","QUALIFIED",
                            "manifestSha256",hash(manifestPath),"httpAttempts",2,
                            "toolDefinitionsSha256",SkillPackageFiles.hash(JSON.writeValueAsBytes(first.path("definitions"))),
                            "scope","This frozen diagnostic candidate/config/tool catalog only; no production capability promotion"));
                }
            }finally{
                result.put("finishedAt",Instant.now().toString());result.put("httpAttempts",capturing==null?0:capturing.delegate.attempts);
                write(control.resolve("result.json"),result);
            }
        }
    }

    static TaskSubmissionResult awaitTask(InteractiveRuntimeAdapter runtime,ForegroundTask task,Path work,
            Path control,long deadline,boolean fixtureResponder,Map<String,Object> result)throws Exception {
                var presented=new HashSet<String>();var handled=new HashSet<String>();
                var approvalStarts=new HashMap<String,Long>();
                var approvalTimings=new ArrayList<Map<String,Object>>();
                while(!task.terminal().isDone()&&System.nanoTime()<deadline){
                    var pending=runtime.pendingApproval();
                    if(pending.isPresent()){
                        var request=pending.get();String key=request.requestId();assertTrue(key.matches("[A-Za-z0-9_-]+"));
                        if(presented.add(key)) {
                            approvalStarts.put(key,System.nanoTime());
                            write(control.resolve("approval-"+key+".json"),request);
                        }
                        Path response=control.resolve("response-"+key+".txt");
                        if(!handled.contains(key)&&(fixtureResponder||Files.isRegularFile(response))){
                            String answer;
                            if(fixtureResponder) {
                                boolean allowed;
                                try{allowed=InvoiceFixtureApproval.allows(work,task.taskId(),request);}
                                catch(Exception unavailable){allowed=false;}
                                answer=allowed?"y":"n";
                                Files.writeString(response,answer,StandardOpenOption.CREATE_NEW);
                            }else answer=Files.readString(response).strip();
                            assertTrue(Set.of("y","n").contains(answer));
                            assertTrue(runtime.respondApproval(key,answer).resolved());
                            handled.add(key);
                            approvalTimings.add(Map.of("requestId",key,"iteration",request.iteration(),
                                    "answer",answer,"responder",fixtureResponder?InvoiceFixtureApproval.POLICY:"manual",
                                    "waitNanos",System.nanoTime()-approvalStarts.get(key)));
                            // Append-free snapshots: intermediate records survive an outer kill.
                            write(control.resolve("approval-timing-"+key+".json"),approvalTimings.getLast());
                        }
                    }
                    Thread.sleep(100);
                }
                if(!task.terminal().isDone()) {
                    write(control.resolve("cancellation-requested.json"),Map.of("reason","DRIVER_DEADLINE",
                            "observedAt",Instant.now().toString(),"approvalTimings",approvalTimings));
                    runtime.requestCancellation();
                }
                result.put("approvalTimings",approvalTimings);
                var terminal=task.terminal().get(15,TimeUnit.SECONDS);
                result.put("status",terminal.status().name());result.put("terminal",terminal);
                assertTrue(runtime.awaitIdle(Duration.ofSeconds(10)));
                return terminal;
    }

    static int authorizedTaskCount(com.fasterxml.jackson.databind.JsonNode manifest){
        int expected=DeliveryRecheckLimits.isRecheck(manifest)?DeliveryRecheckLimits.cases(manifest).size():"interview-validation-v1".equals(manifest.path("kind").asText())?8:5;
        if(DeliveryRecheckLimits.isRecheck(manifest)){
            assertEquals("true",System.getProperty("jdk.httpclient.disableRetryConnect"));
            assertEquals("false",System.getProperty("jdk.httpclient.enableAllMethodRetry"));
            assertEquals("1",System.getProperty("jdk.httpclient.redirects.retrylimit"));
            assertEquals("1",manifest.path("budgetCny").asText());
            assertEquals(DeliveryRecheckLimits.cases(manifest).get(0),manifest.path("rows").get(0).path("case").asText());
            assertEquals(DeliveryRecheckLimits.cases(manifest).get(1),manifest.path("rows").get(1).path("case").asText());
            if(expected==4){
                assertEquals("N3",manifest.path("rows").get(2).path("case").asText());
                assertEquals("N1",manifest.path("rows").get(3).path("case").asText());
            }
        }
        assertEquals(expected,manifest.path("maximumTasks").asInt());
        assertEquals(expected,manifest.path("rows").size());
        return expected;
    }

    static String hash(Path path)throws Exception{return SkillPackageFiles.hash(Files.readAllBytes(path));}
    static void write(Path path,Object value)throws Exception{
        Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardOpenOption.CREATE_NEW);
    }
    static void writeUnchecked(Path path,Object value){try{write(path,value);}catch(Exception e){throw new IllegalStateException("Capture write failed",e);}}

    static final class CapturingProvider implements PreparedTurnProvider,ProviderObservationSource,
            ProviderHttpStatusSource,ProviderTransportEvidenceSource,ProviderRetrySignalSource {
        final EcosystemD7SmokeIT.BoundedProvider delegate;final Path control;final String arm;int turns,decodedResponses;
        boolean captureDecodedResponses;String requiredCatalogHash;
        @Override public boolean usesManagedAnswerEnvelope(){return delegate.usesManagedAnswerEnvelope();}
        @Override public OutputRequirement.Mode managedOutputMode(){return delegate.managedOutputMode();}
        @Override public void validateOutputRequirement(OutputRequirement requirement, boolean hasTools){
            delegate.validateOutputRequirement(requirement,hasTools);
        }
        CapturingProvider(EcosystemD7SmokeIT.BoundedProvider delegate,Path control,String arm){this.delegate=delegate;this.control=control;this.arm=arm;}
        public PreparedProviderTurn prepareTurn(String operation,List<AgentMessage> messages,List<ToolDefinition> definitions){
            return prepareTurn(operation,messages,definitions,OutputRequirement.LEGACY);
        }
        public PreparedProviderTurn prepareTurn(String operation,List<AgentMessage> messages,
                List<ToolDefinition> definitions,OutputRequirement requirement) {
            var actual=treatment(definitions,arm);
            if(requiredCatalogHash!=null)requireCatalog(requiredCatalogHash,JSON.valueToTree(actual));
            var prepared=delegate.prepareTurn(operation,messages,actual,requirement);
            var sanitizer=new PersistenceSanitizer();
            var safe=messages.stream().map(m->new AgentMessage(m.role(),m.content()==null?null:sanitizer.sanitize(m.content()),m.type(),m.toolCallId(),
                    m.toolCallJson()==null?null:sanitizer.sanitize(m.toolCallJson()))).toList();
            writeUnchecked(control.resolve(String.format(Locale.ROOT,"prepared-%04d.json",++turns)),Map.of(
                    "stage","PREPARED_NOT_PROOF_OF_HTTP_SEND","requestDigest",prepared.requestDigest(),"operation",operation,
                    "arm",arm,"messages",safe,"definitions",actual,"outputRequirement",requirement.identity()));
            if(!captureDecodedResponses)return prepared;
            return new PreparedProviderTurn(prepared.requestDigest(),prepared.metrics(),()->{
                ModelResponse response=prepared.execute();
                // Capture only the public, parsed response; never private reasoning or HTTP headers.
                writeUnchecked(control.resolve(String.format(Locale.ROOT,"decoded-%04d.json",++decodedResponses)),Map.of(
                        "stage","PROVIDER_DECODED_BEFORE_AGENT_LOOP_NOT_RAW_HTTP",
                        "requestDigest",prepared.requestDigest(),"type",response.type().name(),
                        "content",sanitizer.sanitize(Objects.toString(response.content(),"")),
                        "toolCallCount",response.toolCalls().size()));
                return response;
            });
        }
        public ProviderCallObservation takeObservation(){return delegate.takeObservation();}
        public Integer takeHttpStatus(){return delegate.takeHttpStatus();}
        public ProviderTransportEvidence takeTransportEvidence(){return delegate.takeTransportEvidence();}
        public ProviderRetrySignal takeRetrySignal(){return delegate.takeRetrySignal();}
    }
}
