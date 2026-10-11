package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.context.ManagedAnswerContract;
import com.paicli.model.*;
import com.paicli.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class P3HarnessContractTest {
    @TempDir Path work;
    @Test void wrongCatalogFailedReadWrongPathAndUnboundObservationAreRejected()throws Exception {
        var json=EcosystemNaturalTaskIT.JSON;
        var definitions=json.readTree("[{\"name\":\"read_file\"}]");
        String hash=com.paicli.skill.SkillPackageFiles.hash(json.writeValueAsBytes(definitions));
        EcosystemNaturalTaskIT.requireCatalog(hash,definitions);
        assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.requireCatalog(hash,json.readTree("[]")));
        var continuation=json.readTree("{\"messages\":[{\"type\":\"OBSERVATION\",\"toolCallId\":\"call-q\",\"content\":\"P3-QUAL-628491\"}]}");
        var good=new com.paicli.state.ToolCallStore.ToolCallRecord(1,"task",1,"call-q","read_file",
                "{\"path\":\"qualification.txt\"}",true,"P3-QUAL-628491",null,"fixture");
        EcosystemNaturalTaskIT.requireQualificationRead(good,continuation);
        var failed=new com.paicli.state.ToolCallStore.ToolCallRecord(1,"task",1,"call-q","read_file",
                good.argumentsJson(),false,"P3-QUAL-628491","failed","fixture");
        assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.requireQualificationRead(failed,continuation));
        var wrong=new com.paicli.state.ToolCallStore.ToolCallRecord(1,"task",1,"call-q","read_file",
                "{\"path\":\"another.txt\"}",true,"P3-QUAL-628491",null,"fixture");
        assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.requireQualificationRead(wrong,continuation));
        ((com.fasterxml.jackson.databind.node.ObjectNode)continuation.path("messages").get(0)).put("toolCallId","unrelated");
        assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.requireQualificationRead(good,continuation));
    }
    OpenAICompatibleProvider encoder()throws Exception {
        var config=EcosystemNaturalTaskIT.JSON.readValue(
                "{\"provider\":\"openai\",\"provider_preset\":\"deepseek\"}",Config.class);
        return new OpenAICompatibleProvider("http://127.0.0.1:1","never-sent","deepseek-v4-flash",
                "fixture",1,OpenAICompatiblePreset.from(config),"disabled",null);
    }
    @Test void missingFailedOrDifferentManifestQualificationCannotOpenBusinessRows()throws Exception {
        var json=EcosystemNaturalTaskIT.JSON;
        var manifest=json.createObjectNode();manifest.putArray("rows").addObject().put("case","Q3").put("paidControl",work.toString());
        Path other=Files.createDirectory(work.resolve("q1"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)manifest.path("rows")).addObject().put("case","Q1").put("paidControl",other.toString());
        Path file=work.resolve("manifest.json");Files.writeString(file,json.writeValueAsString(manifest));
        assertThrows(NoSuchFileException.class,()->EcosystemNaturalTaskIT.requireP3Qualification(manifest,file,"N3"));
        Path proof=work.resolve("qualification.json");
        Files.writeString(proof,json.writeValueAsString(Map.of("status","FAILED","manifestSha256",EcosystemNaturalTaskIT.hash(file))));
        assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.requireP3Qualification(manifest,file,"N3"));
        Files.writeString(proof,"{\"status\":\"QUALIFIED\",\"manifestSha256\":\"different\"}");
        assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.requireP3Qualification(manifest,file,"N3"));
        Files.writeString(proof,json.writeValueAsString(Map.of("status","QUALIFIED","manifestSha256",EcosystemNaturalTaskIT.hash(file))));
        assertThrows(NoSuchFileException.class,()->EcosystemNaturalTaskIT.requireP3Qualification(manifest,file,"N3"));
        Files.writeString(other.resolve("qualification.json"),json.writeValueAsString(Map.of("status","FAILED","manifestSha256",EcosystemNaturalTaskIT.hash(file))));
        assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.requireP3Qualification(manifest,file,"N3"));
        Files.writeString(other.resolve("qualification.json"),Files.readString(proof));
        EcosystemNaturalTaskIT.requireP3Qualification(manifest,file,"N3");
    }
    @Test void p3SelectsBoundedNativeWhileOldRoundsRemainPromptOnly()throws Exception {
        var json=EcosystemNaturalTaskIT.JSON;var manifest=json.readTree(
                "{\"kind\":\"af-p3-v1\",\"outputMode\":\"DIAGNOSTIC_NATIVE_CANDIDATE\"}");
        var row=json.readTree("{\"maximumHttpAttempts\":2,\"maximumToolCalls\":1}");
        var budget=new EcosystemD7SmokeIT.Budget(work.resolve("ledger"),BigDecimal.ONE);
        var p3=DeliveryRecheckLimits.provider(encoder(),budget,"QUAL",manifest,row);
        assertEquals(2,p3.maxRequests);assertEquals(1,p3.maxToolCalls);
        assertEquals(OutputRequirement.Mode.DIAGNOSTIC_NATIVE_CANDIDATE,p3.managedOutputMode());
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("outputMode","PROMPT_ONLY");
        assertThrows(IllegalArgumentException.class,()->DeliveryRecheckLimits.provider(encoder(),budget,"QUAL",manifest,row));
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("kind","quality-recheck-v1");
        assertEquals(OutputRequirement.Mode.PROMPT_ONLY,DeliveryRecheckLimits.provider(encoder(),budget,"old",manifest,row).managedOutputMode());
        assertFalse(Files.exists(work.resolve("ledger")));
    }
    @Test void zeroCallPreflightUsesActualNativeSerializerAndToolDefinitions()throws Exception {
        var provider=EcosystemNaturalTaskIT.managedPreflight((messages,tools)->ModelResponse.finalAnswer("fixture"),work,encoder());
        var tools=List.of(new ToolDefinition("read_file","Read",EcosystemNaturalTaskIT.JSON.valueToTree(
                Map.of("type","object","properties",Map.of()))));
        var response=provider.chatForOperation("CHAT",List.of(AgentMessage.userGoal(ManagedAnswerContract.INSTRUCTION)),
                tools,OutputRequirement.managed(OutputRequirement.Mode.DIAGNOSTIC_NATIVE_CANDIDATE));
        assertEquals("fixture",ManagedAnswerContract.decode(response.content()));
        var evidence=EcosystemNaturalTaskIT.JSON.readTree(Files.readString(work.resolve("preflight-native-0001.json")));
        assertEquals(0,evidence.path("realHttpAttempts").asInt());
        assertTrue(evidence.path("outputRequirement").asText().endsWith("DIAGNOSTIC_NATIVE_CANDIDATE"));
        assertEquals(1,evidence.path("metrics").path("toolCount").asInt());
    }
}
