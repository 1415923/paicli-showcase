package com.paicli.skill;

import com.paicli.config.Config;
import com.paicli.model.ToolCall;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.state.*;
import com.paicli.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillEcosystemTest {
    @TempDir Path workspace;

    @Test void completeImportIsInertConfirmedAndBytePreserving()throws Exception {
        Path source=write("download/original","standard","private-body");
        Files.createDirectories(source.resolve("assets/empty"));
        Files.createDirectories(source.resolve("scripts"));
        byte[] binary={0,1,2,(byte)255};Files.write(source.resolve("assets/logo.bin"),binary);
        Files.writeString(source.resolve("scripts/check.py"),"raise RuntimeError('import must not run me')\n");
        Files.writeString(source.resolve("LICENSE"),"fixture license\r\n");
        var commands=new SkillCommands(workspace,Config.DEFAULT_SKILLS_DIRS);
        var before=SkillPackageFiles.snapshot(source);
        String canonical="{\"directories\":"+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(before.directories())
                +",\"files\":"+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(before.hashes())+"}";
        assertEquals(SkillPackageFiles.hash(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)),before.hash(),"package identity uses fixed cross-process field ordering");
        var preview=commands.command(List.of("import",source.toString()),false);
        assertEquals("SKILLS",preview.code(),preview.toString());
        assertFalse(Files.exists(workspace.resolve(".paicli/skills/standard")));
        assertEquals("SKILL_COMMAND_REJECTED",commands.command(List.of("import",source.toString(),"wrong"),false).code());
        assertEquals("SKILL_COMMAND_REJECTED",commands.command(List.of("import",source.toString(),before.hash()),true).code());
        var imported=commands.command(List.of("import",source.toString(),before.hash()),false);
        assertEquals("SKILLS",imported.code(),imported.toString());
        assertEquals(before.hash(),SkillPackageFiles.snapshot(workspace.resolve(".paicli/skills/standard")).hash());
        assertArrayEquals(binary,Files.readAllBytes(workspace.resolve(".paicli/skills/standard/assets/logo.bin")));
        assertEquals("SKILL_COMMAND_REJECTED",commands.command(List.of("import",source.toString(),before.hash()),false).code());
        assertEquals("SKILLS",commands.command(List.of("show","standard"),true).code());
    }

    @Test void draftRemainsInactiveAndChangedConfirmationIsRejected()throws Exception {
        var commands=new SkillCommands(workspace,Config.DEFAULT_SKILLS_DIRS);
        assertEquals("SKILLS",commands.command(List.of("init","created"),false).code());
        assertTrue(commands.command(List.of("list"),false).lines().getFirst().contains("No Skills"));
        Path draft=workspace.resolve("skill-drafts/created");String hash=SkillPackageFiles.snapshot(draft).hash();
        Files.writeString(draft.resolve("reference.md"),"new content");
        assertEquals("SKILL_COMMAND_REJECTED",commands.command(List.of("import",draft.toString(),hash),false).code());
        assertFalse(Files.exists(workspace.resolve(".paicli/skills/created")));
    }

    @Test void discoveryDoesNotInjectBodyAndModelLoadsPaginatedResourcesWithAudit()throws Exception {
        Path source=write("skills/alpha","alpha","UNIQUE_BODY_INSTRUCTION");
        Files.createDirectories(source.resolve("references"));
        Files.writeString(source.resolve("references/large.md"),"RESOURCE_SECRET_MARKER\n"+"x".repeat(110000));
        try(var state=state()){
            var runtime=runtime(state);var context=runtime.select("task","run","PLAN","plan","review java",null,null);
            assertTrue(context.context().contains("alpha"));assertFalse(context.context().contains("UNIQUE_BODY_INSTRUCTION"));
            assertFalse(context.context().contains("RESOURCE_SECRET_MARKER"));assertTrue(context.selectedSkillNames().isEmpty());
            var registry=new ToolRegistry();runtime.registerTools(registry,"task","run");
            var load=call(runtime,registry,"load",new ToolCall("load_skill",Map.of("name","alpha")));
            assertTrue(load.success(),load.error());assertTrue(load.output().contains("UNIQUE_BODY_INSTRUCTION"));
            assertFalse(load.output().contains("RESOURCE_SECRET_MARKER"));
            var read=call(runtime,registry,"read",new ToolCall("read_skill_resource",Map.of("name","alpha","path","references/large.md","max_chars",512)));
            assertTrue(read.success(),read.error());assertTrue(read.output().contains("RESOURCE_SECRET_MARKER"));
            assertEquals(512,read.metadata().get("nextOffset"));assertEquals(false,read.metadata().get("eof"));
            assertFalse(call(runtime,registry,"escape",new ToolCall("read_skill_resource",Map.of("name","alpha","path","../SKILL.md"))).success());
            assertEquals(List.of("alpha"),state.skillSelections.findSelectedByRun("run").stream().map(SkillSelectionStore.SelectedSkillRecord::skillName).toList());
            assertTrue(state.skillLoads.accessesForRun("run").stream().anyMatch(a->"read".equals(a.toolCallId())&&"occ-read".equals(a.occurrenceId())&&a.resourcePath().equals("references/large.md")));
            assertTrue(state.skillLoads.reportForTaskRun("task","run").stream().anyMatch(line->line.contains("occ-read")));
        }
    }

    @Test void sanitizedPagesFitBudgetWithoutSkippingSourceCharacters()throws Exception {
        Path source=write("skills/alpha","alpha","body");
        String original="token:a\n".repeat(1984);
        Files.writeString(source.resolve("expanding.md"),original);
        try(var state=state()){
            var runtime=runtime(state);runtime.select("task","run","PLAN","explicit","$alpha",null,null);
            var registry=new ToolRegistry();runtime.registerTools(registry,"task","run");
            int offset=0,pages=0;
            do {
                var result=call(runtime,registry,"page-"+pages,new ToolCall("read_skill_resource",Map.of("name","alpha","path","expanding.md","offset",offset,"max_chars",15872)));
                assertTrue(result.success(),result.error());assertTrue(result.output().length()<=Config.defaults().effectiveMaxSkillContextChars());
                int next=(Integer)result.metadata().get("nextOffset");assertTrue(next>offset);
                String body=result.output().substring(result.output().indexOf('\n',result.output().indexOf('\n')+1)+1);
                assertEquals(new PersistenceSanitizer().sanitize(original.substring(offset,next)),body);
                offset=next;pages++;
            }while(offset<original.length());
            assertTrue(pages>1);assertEquals(original.length(),offset);
            assertEquals(original.length(),state.skillLoads.accessesForRun("run").stream().filter(a->a.kind().equals("READ")).mapToInt(SkillLoadStore.Access::chars).sum());
        }
    }

    @Test void explicitUnknownFailsAndResourceChangesCannotMasqueradeAsFrozenInput()throws Exception {
        Path source=write("skills/alpha","alpha","UNIQUE_BODY");Files.writeString(source.resolve("reference.md"),"before");
        var before=new RuntimeManifestHasher(workspace).compute(Config.defaults());
        try(var state=state()){
            var runtime=runtime(state);
            assertThrows(SkillRuntime.SkillRuntimeException.class,()->runtime.select("task","run","PLAN","unknown","$not-installed",null,null));
            var selected=runtime.select("task","run","PLAN","explicit","use $alpha",null,null);
            assertEquals(List.of("alpha"),selected.selectedSkillNames());assertTrue(selected.context().contains("UNIQUE_BODY"));
            Files.writeString(source.resolve("reference.md"),"changed");
            assertThrows(SkillRuntime.SkillRuntimeException.class,()->runtime.validateFrozen("run"));
            assertThrows(SkillRuntime.SkillRuntimeException.class,()->runtime(state).select("task","run","PLAN","explicit","use $alpha",null,null));
            assertNotEquals(before.skillsSha256(),new RuntimeManifestHasher(workspace).compute(Config.defaults()).skillsSha256());
        }
    }

    @Test void scriptResourcesAreControlPlaneAndHintsDoNotGrantCommandPermission()throws Exception {
        Path source=write("skills/alpha","alpha","body");
        var definition=SkillLoader.inspectPackage(source);
        assertEquals(List.of("run_command"),definition.allowedTools());assertTrue(definition.triggers().isEmpty());
        assertTrue(new ControlPlanePaths(workspace,Config.DEFAULT_SKILLS_DIRS).isControlPlane(Path.of("skills/alpha/scripts/check.py")));
        assertFalse(new com.paicli.safety.ApprovalPolicy(Config.defaults().withApprovalMode("read-only"),"task").evaluate("run_command",false).isAllowed());
    }

    @Test void completeResourceIndexCanBePagedWithoutReloadingBodyOrGuessingPaths() throws Exception {
        Path source=write("skills/alpha","alpha","BODY_MUST_NOT_REPEAT_IN_INDEX_PAGES");
        Path references=Files.createDirectories(source.resolve("references"));
        for(int i=0;i<400;i++)Files.writeString(references.resolve("rule-%03d-with-a-long-descriptive-name.md".formatted(i)),"rule "+i);
        var expected=SkillPackageFiles.snapshot(source).hashes().keySet().stream().sorted().toList();
        try(var state=state()){
            var runtime=runtime(state);runtime.select("task","run","PLAN","plan","review",null,null);
            var registry=new ToolRegistry();runtime.registerTools(registry,"task","run");
            assertFalse(call(runtime,registry,"inactive",new ToolCall("load_skill",Map.of("name","alpha","resource_offset",0))).success());
            var first=call(runtime,registry,"first",new ToolCall("load_skill",Map.of("name","alpha")));
            assertTrue(first.success(),first.error());
            assertTrue(first.output().contains("resource_offset="));
            var seen=new ArrayList<String>();int offset=0,pages=0;
            do {
                var result=call(runtime,registry,"index-"+pages,new ToolCall("load_skill",Map.of("name","alpha","resource_offset",offset)));
                assertTrue(result.success(),result.error());
                assertFalse(result.output().contains("BODY_MUST_NOT_REPEAT_IN_INDEX_PAGES"));
                assertTrue(result.output().length()<=Config.defaults().effectiveMaxSkillContextChars());
                seen.addAll(result.output().lines().filter(expected::contains).toList());
                int next=(Integer)result.metadata().get("nextResourceOffset");
                assertTrue(next>offset);offset=next;pages++;
            }while(offset<expected.size());
            assertTrue(pages>1);
            assertEquals(expected,seen);
            var eof=call(runtime,registry,"eof",new ToolCall("load_skill",Map.of("name","alpha","resource_offset",expected.size())));
            assertTrue(eof.success(),eof.error());assertEquals(true,eof.metadata().get("resourcesEof"));
            var last=call(runtime,registry,"last",new ToolCall("read_skill_resource",Map.of("name","alpha","path",seen.getLast())));
            assertTrue(last.success(),last.error());
            var missing=call(runtime,registry,"missing",new ToolCall("read_skill_resource",Map.of("name","alpha","path","absent.md")));
            assertFalse(missing.success());assertTrue(missing.error().contains("resource_offset"));
            var invalid=call(runtime,registry,"invalid",new ToolCall("load_skill",Map.of("name","alpha","resource_offset",expected.size()+1)));
            assertFalse(invalid.success());
            assertTrue(state.skillLoads.accessesForRun("run").stream().anyMatch(a->"index-0".equals(a.toolCallId())&&a.status().equals("INDEX")));
            assertEquals(1,state.skillLoads.accessesForRun("run").stream().filter(a->a.kind().equals("ACTIVATE")).count());
            var restored=runtime(state);restored.select("task","run","PLAN","restored","review",null,null);
            var restoredRegistry=new ToolRegistry();restored.registerTools(restoredRegistry,"task","run");
            assertTrue(call(restored,restoredRegistry,"restored-index",new ToolCall("load_skill",Map.of("name","alpha","resource_offset",0))).success());
            Files.writeString(references.resolve("new-rule.md"),"changed package");
            assertThrows(SkillRuntime.SkillRuntimeException.class,()->call(restored,restoredRegistry,"drift",new ToolCall("load_skill",Map.of("name","alpha","resource_offset",0))));
        }
    }

    @Test void unicodeIndexPagesSurviveTheActualContextProviderByteBoundary() throws Exception {
        Path source=write("skills/alpha","alpha","body");
        Path references=Files.createDirectories(source.resolve("references"));
        for(int i=0;i<600;i++)Files.writeString(references.resolve("资".repeat(60)+"-%04d.md".formatted(i)),"reference");
        var expected=SkillPackageFiles.snapshot(source).hashes().keySet().stream().sorted().toList();
        Config config=Config.defaults().merge(new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"max_skill_context_chars\":65536}",Config.class));
        try(var state=state()){
            var sanitizer=new PersistenceSanitizer();
            var runtime=new SkillRuntime(new SkillLoader(workspace,state.skillLoads,sanitizer),new SkillSelector(state.skillSelections,sanitizer),
                    new SkillContextBuilder(sanitizer),state.skillSelections,config);
            runtime.select("task","run","PLAN","explicit","$alpha",null,null);
            var registry=new ToolRegistry();runtime.registerTools(registry,"task","run");
            var seen=new ArrayList<String>();int offset=0,pages=0;
            do {
                var result=call(runtime,registry,"unicode-"+pages,new ToolCall("load_skill",Map.of("name","alpha","resource_offset",offset)));
                assertTrue(result.success(),result.error());
                assertIndexReachesProvider(result,offset,pages);
                seen.addAll(result.output().lines().filter(expected::contains).toList());
                int next=(Integer)result.metadata().get("nextResourceOffset");assertTrue(next>offset);
                offset=next;pages++;
            }while(offset<expected.size());
            assertTrue(pages>1);assertEquals(expected,seen);
        }
    }

    private void assertIndexReachesProvider(ToolResult result,int offset,int page)throws Exception {
        Path contextWorkspace=Files.createDirectories(workspace.resolve("context-page-"+page));
        try(var state=new StateStore(contextWorkspace)){
            var admitted=com.paicli.support.R3ContextTestFixtures.admit(state,contextWorkspace);
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var args=Map.<String,Object>of("name","alpha","resource_offset",offset);
            String arguments=json.writeValueAsString(args),id="index-call";
            var wire=json.createObjectNode().put("id",id).put("type","function");
            wire.putObject("function").put("name","load_skill").put("arguments",arguments);
            var goal=com.paicli.model.AgentMessage.userGoal("Use the listed resource paths.");
            var action=com.paicli.model.AgentMessage.actionWithToolCall("Read index",id,wire.toString());
            var observation=com.paicli.model.AgentMessage.observationForToolCall(result.output(),id);
            admitted.guard().appendEvent(0,goal);admitted.guard().appendEvent(1,action);
            var occurrence=admitted.guard().admitOccurrence(1,id,new ToolCall("load_skill",args,id));
            admitted.guard().recordToolCall(1,id,"load_skill",arguments,result);
            admitted.guard().completeOccurrence(occurrence,result);admitted.guard().appendEvent(1,observation);
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var provider=new com.paicli.context.ContextualModelProvider((messages,tools)->{
                calls.incrementAndGet();
                assertTrue(messages.stream().anyMatch(m->m.type()==com.paicli.model.AgentMessage.MessageType.OBSERVATION
                        &&result.output().equals(m.content())),"the entire index and cursor must reach the model without byte truncation");
                return com.paicli.model.ModelResponse.finalAnswer("fixture");
            },state,contextWorkspace,admitted.root().taskId().value(),"mock","model",com.paicli.context.ContextModels.OperationKind.CHAT,
                    new com.paicli.context.ContextModels.CapabilityProfile("mock",131072,
                            com.paicli.context.ContextModels.LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),false,"runtime");
            var response=provider.chat(com.paicli.context.ContextModels.OperationKind.TOOL_CONTINUATION,"2",List.of(goal,action,observation),List.of(),admitted.guard());
            admitted.guard().completeOccurrence(com.paicli.identity.CanonicalId.parse(response.providerOccurrenceId()),ToolResult.success("done"));
            assertEquals(1,calls.get());
        }
    }

    private ToolResult call(SkillRuntime runtime,ToolRegistry registry,String id,ToolCall call){
        return runtime.executeTool("run",id,"occ-"+id,call,()->registry.invoke(call));
    }
    private StateStore state()throws Exception {
        var state=new StateStore(workspace);state.tasks.createTask("task","goal","mock",null,"on-request",10);
        state.codingRuns.create("run","task",CodingRunStore.KIND_INITIAL,"",1,0);return state;
    }
    private SkillRuntime runtime(StateStore state)throws Exception {
        var sanitizer=new PersistenceSanitizer();return new SkillRuntime(new SkillLoader(workspace,state.skillLoads,sanitizer),
                new SkillSelector(state.skillSelections,sanitizer),new SkillContextBuilder(sanitizer),state.skillSelections,Config.defaults());
    }
    private Path write(String directory,String name,String body)throws Exception {
        Path path=Files.createDirectories(workspace.resolve(directory));
        Files.writeString(path.resolve("SKILL.md"),"---\nname: "+name+"\ndescription: Review Java fixtures\nlicense: MIT\nmetadata:\n  author: original\ncompatibility: Python optional\nallowed-tools: run_command\n---\n"+body);
        return path;
    }
}
