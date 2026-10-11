package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.telemetry.ProviderCallObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NaturalTreatmentTest {
    @TempDir Path temporary;
    @Test void treatmentChangesOnlyOneDescriptionAndNeverMutatesOriginal(){
        var schema=new ObjectMapper().createObjectNode().put("type","object");
        var command=new ToolDefinition("run_command","current shell facts",schema);
        var file=new ToolDefinition("read_file","read",schema);
        var tools=List.of(command,file);
        var before=EcosystemNaturalTaskIT.treatment(tools,"before");
        assertEquals(EcosystemNaturalTaskIT.OLD_DESCRIPTION,before.getFirst().description());
        assertEquals(command.name(),before.getFirst().name());assertSame(schema,before.getFirst().parameterSchema());
        assertSame(file,before.get(1));assertEquals("current shell facts",command.description());
        assertEquals(tools,EcosystemNaturalTaskIT.treatment(tools,"after"));
        assertThrows(IllegalArgumentException.class,()->EcosystemNaturalTaskIT.treatment(tools,"unknown"));
    }
    @Test void lowerBudgetStopsOutstandingAttemptsAndKeepsD7Default()throws Exception{
        var limited=new EcosystemD7SmokeIT.Budget(temporary.resolve("new.jsonl"),new BigDecimal("10"));
        limited.reserve("N2-before","a".repeat(64));limited.reserve("N2-after","b".repeat(64));
        assertThrows(IllegalStateException.class,()->limited.reserve("N1-after","c".repeat(64)));
        assertThrows(IllegalStateException.class,()->new EcosystemD7SmokeIT.Budget(temporary.resolve("new.jsonl"),new BigDecimal("10")).reserve("N3-after","d".repeat(64)));
        var old=new EcosystemD7SmokeIT.Budget(temporary.resolve("old.jsonl"));
        for(int i=0;i<5;i++)old.reserve("E1","a".repeat(64));
        assertThrows(IllegalStateException.class,()->old.reserve("E2","b".repeat(64)));
    }
    @Test void completeUsageSettlesAndMissingUsageCannotBecomeFree()throws Exception{
        var budget=new EcosystemD7SmokeIT.Budget(temporary.resolve("usage.jsonl"),new BigDecimal("10"));
        String id=budget.reserve("N2-before","a".repeat(64));
        budget.settle(id,new ProviderCallObservation(1000,100,1100,600,null,null,
                ProviderCallObservation.UsageSource.PROVIDER_REPORTED,ProviderCallObservation.UsageCoverage.COMPLETE,
                null,null,null,null,null,false));
        assertEquals(0,budget.spent.compareTo(new BigDecimal("0.002160")));
        budget.settle(budget.reserve("N2-after","b".repeat(64)),ProviderCallObservation.notExposed());
        assertThrows(IllegalStateException.class,()->budget.reserve("N1-after","c".repeat(64)));
    }
}
