package com.paicli.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPreflightFailureKindTest {
    @Test void modelPolicyAndIntegrityFailuresRemainTyped(){
        ToolRegistry registry=new ToolRegistry();
        assertEquals(ToolRegistry.PreflightFailureKind.MODEL_CORRECTABLE,
                registry.preflight(new ToolCall("missing",Map.of())).failureKind());

        registry.register(tool("policy",ToolResult.error("path denied"),false));
        assertEquals(ToolRegistry.PreflightFailureKind.POLICY_DENIED,
                registry.preflight(new ToolCall("policy",Map.of())).failureKind());

        registry.register(tool("integrity",ToolResult.success(""),true));
        assertEquals(ToolRegistry.PreflightFailureKind.INTEGRITY_FAILURE,
                registry.preflight(new ToolCall("integrity",Map.of())).failureKind());
    }

    private static Tool tool(String name,ToolResult preflight,boolean brokenProjection){
        return new Tool(){
            @Override public String name(){return name;}
            @Override public String description(){return "test";}
            @Override public ToolDefinition toDefinition(){
                var schema=JsonNodeFactory.instance.objectNode();schema.put("type","object");
                schema.put("additionalProperties",false);
                return new ToolDefinition(name,"test",schema);
            }
            @Override public ToolResult preflightCall(ToolCall call){return preflight;}
            @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call){
                if(brokenProjection)throw new IllegalStateException("projection broken");
                return Optional.empty();
            }
            @Override public ToolResult execute(ToolCall call){return ToolResult.success("unused");}
        };
    }
}
