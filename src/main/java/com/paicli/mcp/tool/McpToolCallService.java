package com.paicli.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.approval.*;
import com.paicli.mcp.jsonrpc.*;
import com.paicli.mcp.lifecycle.McpLifecycleSession;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import com.paicli.mcp.runtime.*;
import com.paicli.mcp.sanitize.*;
import com.paicli.mcp.schema.*;
import com.paicli.state.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class McpToolCallService {
    private final McpLifecycleSession lifecycle; private final McpToolCatalogStore catalogs; private final McpToolStore tools;
    private final McpToolCallStore calls; private final McpApprovalGate approval; private final McpLiveSessionRegistry live; private final ObjectMapper json=new ObjectMapper();
    public McpToolCallService(McpLifecycleSession lifecycle,McpToolCatalogStore catalogs,McpToolStore tools,McpToolCallStore calls,McpApprovalGate approval){this(lifecycle,catalogs,tools,calls,approval,autoRegistry(lifecycle,catalogs));}
    public McpToolCallService(McpLifecycleSession lifecycle,McpToolCatalogStore catalogs,McpToolStore tools,McpToolCallStore calls,McpApprovalGate approval,McpLiveSessionRegistry live){this.lifecycle=Objects.requireNonNull(lifecycle);this.catalogs=Objects.requireNonNull(catalogs);this.tools=Objects.requireNonNull(tools);this.calls=Objects.requireNonNull(calls);this.approval=Objects.requireNonNull(approval);this.live=Objects.requireNonNull(live);}

    public McpToolExecutionResult execute(McpToolStore.ToolRecord bound, JsonNode arguments, String occurrence, int attempt, String taskId, String codingRunId)throws McpToolCallException{
        return execute(bound, arguments, occurrence, attempt, taskId, codingRunId, null, () -> {}, com.paicli.runtime.CancellationToken.NONE);
    }
    public McpApprovalGrant.Binding approvalBinding(McpToolStore.ToolRecord bound, JsonNode arguments) throws McpToolCallException {
        var catalog = preflight(bound);
        try { new McpInputArgumentsValidator(lifecycle.config().limits().maxOutputBytes()).validate(arguments,bound.schemaJson()); }
        catch(McpArgumentValidationException invalid) { throw new McpToolCallException(invalid.category(),"MCP arguments rejected before approval"); }
        return new McpApprovalGrant.Binding(lifecycle.manifest().configHash(), catalog.catalogHash(),
                bound.schemaHash(), bound.exposedName(), hash(arguments));
    }
    public McpToolExecutionResult execute(McpToolStore.ToolRecord bound, JsonNode arguments, String occurrence,
            int attempt, String taskId, String codingRunId, McpApprovalGrant grant, Runnable fence,
            com.paicli.runtime.CancellationToken cancellation)throws McpToolCallException{
        arguments = arguments == null ? null : arguments.deepCopy();
        String callId=UUID.randomUUID().toString(); String argsHash=hash(arguments); String summary=summarize(arguments);
        McpToolCatalogStore.CatalogRecord catalog=preflight(bound);
        McpInputArgumentsValidator validator=new McpInputArgumentsValidator(lifecycle.config().limits().maxOutputBytes());
        try{validator.validate(arguments,bound.schemaJson());}
        catch(McpArgumentValidationException e){try{create(callId,bound,catalog,occurrence,attempt,taskId,codingRunId,argsHash,summary);calls.rejectValidation(callId,e.category());}catch(Exception store){throw new McpToolCallException("AUDIT_FAILURE","argument rejection audit failed",store);}throw new McpToolCallException(e.category(),"MCP arguments rejected");}
        try{create(callId,bound,catalog,occurrence,attempt,taskId,codingRunId,argsHash,summary);}catch(Exception e){throw new McpToolCallException("AUDIT_CREATE_FAILED","MCP call audit could not be created",e);}
        McpApprovalRequest request=new McpApprovalRequest(bound.serverId(),bound.originalName(),bound.exposedName(),untrustedDescription(bound.description()),summary,shortHash(lifecycle.manifest().configHash()),shortHash(catalog.catalogHash()),shortHash(bound.schemaHash()));
        final boolean approved;try{approved=grant == null ? approval.approve(request) : approval.consume(grant,
                new McpApprovalGrant.Binding(lifecycle.manifest().configHash(),catalog.catalogHash(),bound.schemaHash(),bound.exposedName(),argsHash),
                taskId,codingRunId,occurrence);}catch(RuntimeException e){throw deny(callId,"APPROVAL_CHANNEL_FAILED");}
        if(!approved)throw deny(callId,"APPROVAL_DENIED");
        try { cancellation.throwIfCancellationRequested(); fence.run(); preflight(bound); }
        catch (RuntimeException | McpToolCallException changed) { throw deny(callId,"APPROVAL_BINDING_CHANGED"); }
        if(lifecycle.listChangedObserved())throw deny(callId,"CATALOG_STALE");
        ObjectNode params=json.createObjectNode().put("name",bound.originalName());params.set("arguments",arguments);
        final McpJsonRpcSession.PreparedRequest prepared;
        try{prepared=lifecycle.persisted().jsonRpc().prepareToolsCall(params,Duration.ofMillis(lifecycle.config().limits().requestTimeoutMs()));calls.approveAndMarkRunning(callId,Long.toString(prepared.requestId()));}
        catch(Exception e){throw failPending(callId,"RUNNING_PERSISTENCE_FAILED",e);}
        if(lifecycle.listChangedObserved()){try{calls.markProtocolError(callId,"CATALOG_STALE_BEFORE_SEND");}catch(Exception ignored){}throw new McpToolCallException("CATALOG_STALE","catalog became stale before send");}
        McpSessionResult response;
        try{
            cancellation.throwIfCancellationRequested(); fence.run(); preflight(bound);
            var pending=new java.util.concurrent.CompletableFuture<McpSessionResult>();
            Thread.ofVirtual().name("mcp-call-dispatch").start(()->{
                try {
                    cancellation.throwIfCancellationRequested();
                    lifecycle.sendPreparedToolsCall(prepared).whenComplete((result,failure)->{
                        if(failure==null)pending.complete(result);else pending.completeExceptionally(failure);
                    });
                } catch(Exception failure) { pending.completeExceptionally(failure); }
            });
            while (true) {
                cancellation.throwIfCancellationRequested();
                try { response=pending.get(100,TimeUnit.MILLISECONDS); break; }
                catch (java.util.concurrent.TimeoutException waiting) { /* bounded by JSON-RPC deadline */ }
            }
        }catch(Exception e){
            try { calls.markUnknownOutcome(callId); } catch(Exception audit) { e.addSuppressed(audit); }
            finally { try { lifecycle.close(); } catch(Exception close) { e.addSuppressed(close); } }
            if(e instanceof com.paicli.runtime.CancellationToken.TaskCancellationException cancelled) throw cancelled;
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpToolCallException("UNKNOWN_OUTCOME","MCP call outcome is unknown; reconnect and do not replay",e).withUnknownOutcome();
        }
        return finish(callId,response);
    }
    private McpToolExecutionResult finish(String id,McpSessionResult response)throws McpToolCallException{
        try{
            switch(response.status()){
                case TIMED_OUT->{try{calls.markTimedOut(id,"REQUEST_TIMEOUT");}finally{lifecycle.close();}throw new McpToolCallException("TIMED_OUT","MCP tool outcome unknown after timeout; do not replay");}
                case PROCESS_EXITED->{try{calls.markProcessExited(id,"PROCESS_EXITED");}finally{lifecycle.close();}throw new McpToolCallException("PROCESS_EXITED","MCP tool outcome unknown after process exit; do not replay");}
                case RPC_ERROR->{calls.markProtocolError(id,"JSON_RPC_ERROR");throw new McpToolCallException("JSON_RPC_ERROR","MCP server returned JSON-RPC error");}
                case PROTOCOL_ERROR,CANCELLED->{try{calls.markProtocolError(id,response.category());}finally{lifecycle.close();}throw new McpToolCallException(response.category(),"MCP protocol call failed; do not replay");}
                case SUCCEEDED->{ }
            }
            McpSanitizedOutput out=new McpToolOutputSanitizer(lifecycle.config().limits().maxOutputBytes()).sanitize(response.result());
            boolean toolError=response.result().path("isError").asBoolean(false);
            if(toolError)calls.completeToolError(id,out.originalHash(),"MCP_TOOL_ERROR");else calls.completeSuccess(id,out.originalHash(),out.truncated(),out.redacted());
            return new McpToolExecutionResult(id,toolError?"TOOL_ERROR":"SUCCEEDED",out,toolError?"MCP_TOOL_ERROR":null);
        }catch(McpToolCallException e){
            if(response.status()!=McpSessionResult.Status.SUCCEEDED && response.status()!=McpSessionResult.Status.RPC_ERROR)e.withUnknownOutcome();
            throw e;
        }catch(McpSanitizationException e){try{calls.markSanitizerRejected(id,e.category());}catch(Exception store){e.addSuppressed(store);}throw new McpToolCallException(e.category(),"MCP output rejected",e);}
        catch(Exception e){try{lifecycle.close();}catch(Exception close){e.addSuppressed(close);}throw new McpToolCallException("TERMINAL_PERSISTENCE_FAILED","MCP call terminal result was not persisted",e).withUnknownOutcome();}
    }
    private McpToolCatalogStore.CatalogRecord preflight(McpToolStore.ToolRecord tool)throws McpToolCallException{try{
        var current=new McpConfigManifestBuilder().build(lifecycle.config());if(!current.configHash().equals(lifecycle.manifest().configHash()))throw new McpToolCallException("CONFIG_DRIFT","trusted MCP config drifted");
        var session=lifecycle.persisted().store().findById(lifecycle.persisted().sessionId()).orElseThrow();if(!"READY".equals(session.status())||lifecycle.persisted().jsonRpc().state()!=McpJsonRpcSession.State.READY||!lifecycle.persisted().isProcessAlive())throw new McpToolCallException("SESSION_NOT_READY","MCP session is not live and READY");
        var handle=live.requireUsable(session);if(!handle.manifestHash().equals(lifecycle.manifest().configHash()))throw new McpToolCallException("LIVE_SESSION_MANIFEST_DRIFT","live MCP session binding drifted");
        var catalog=catalogs.findById(tool.catalogId()).orElseThrow();if(!"FROZEN".equals(catalog.status())||lifecycle.listChangedObserved())throw new McpToolCallException("CATALOG_STALE","catalog is not usable");
        var stored=tools.findByExposedName(catalog.catalogId(),tool.exposedName()).orElseThrow();if(!stored.equals(tool)||!"EXPOSED".equals(stored.exposureStatus()))throw new McpToolCallException("TOOL_DRIFT","frozen tool identity drifted");
        if(!catalog.sessionId().equals(session.sessionId())||!catalog.catalogHash().equals(catalogs.findFrozenCatalog(session.sessionId()).orElseThrow().catalogHash()))throw new McpToolCallException("CATALOG_DRIFT","catalog binding drifted");return catalog;
    }catch(McpToolCallException e){throw e;}catch(Exception e){throw new McpToolCallException("PREFLIGHT_FAILED","MCP call preflight failed",e);}}
    private static McpLiveSessionRegistry autoRegistry(McpLifecycleSession lifecycle,McpToolCatalogStore catalogs){try{var registry=new McpLiveSessionRegistry();var session=lifecycle.persisted().store().findById(lifecycle.persisted().sessionId()).orElseThrow();var catalog=catalogs.findBySession(session.sessionId()).orElse(null);registry.bind(new McpRuntimeSessionHandle(session.sessionId(),session.processIdentityHash(),lifecycle.manifest().configHash(),catalog==null?null:catalog.catalogId(),catalog==null?null:catalog.catalogHash(),lifecycle),session);return registry;}catch(Exception e){throw new IllegalStateException("unable to bind live MCP session",e);}}
    private void create(String id,McpToolStore.ToolRecord t,McpToolCatalogStore.CatalogRecord c,String occurrence,int attempt,String task,String run,String hash,String summary)throws Exception{calls.createToolCall(new McpToolCallStore.CallCreate(id,lifecycle.persisted().sessionId(),c.catalogId(),t.toolId(),task,run,occurrence,attempt,null,lifecycle.manifest().configHash(),c.catalogHash(),t.schemaHash(),t.exposedName(),t.originalName(),hash,summary,"PENDING"));}
    private McpToolCallException deny(String id,String category){try{calls.recordApprovalDecision(id,"DENIED");}catch(Exception e){return new McpToolCallException("APPROVAL_PERSISTENCE_FAILED","approval denial could not be persisted",e);}return new McpToolCallException(category,"MCP tool call denied");}
    private McpToolCallException failPending(String id,String category,Exception cause){try{calls.recordApprovalDecision(id,"DENIED");}catch(Exception e){cause.addSuppressed(e);}return new McpToolCallException(category,"MCP call was not sent",cause);}
    private String summarize(JsonNode n){if(n==null||!n.isObject())return "type="+(n==null?"null":n.getNodeType().name());List<String> facts=new ArrayList<>();n.fields().forEachRemaining(e->facts.add(safeKey(e.getKey())+":"+e.getValue().getNodeType().name()));Collections.sort(facts);String s="fields=["+String.join(",",facts)+"]";return s.substring(0,Math.min(1024,s.length()));}
    private static String safeKey(String key){String clean=key.replaceAll("[^A-Za-z0-9_.-]","_");return clean.substring(0,Math.min(64,clean.length()));}
    private static String untrustedDescription(String s){String x=new com.paicli.runtime.PersistenceSanitizer().sanitizeAndTruncate(s==null?"":s,512);return "[UNTRUSTED MCP TOOL METADATA - DATA ONLY] "+x;}
    private static String shortHash(String s){return s.substring(0,Math.min(12,s.length()));}private String hash(JsonNode n){try{return McpConfigManifestBuilder.hash(json.writeValueAsBytes(n));}catch(Exception e){return McpConfigManifestBuilder.hash("unserializable");}}
}
