package com.paicli.mcp.interactive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.config.*;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import com.paicli.mcp.runtime.McpClientRuntime;
import com.paicli.interactive.command.ShellCommandResult;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.*;
import com.paicli.tool.ToolRegistry;
import java.nio.file.*;
import java.util.*;

/** Shell-owned single connection. Import is inert; confirmation applies to exact startup bytes. */
public final class InteractiveMcpService implements AutoCloseable {
    private final Path workspace, configFile;
    private final StateStore state;
    private final McpClientRuntime runtime;
    private final ObjectMapper json = new ObjectMapper();
    private McpServerConfig selected;
    private McpClientRuntime.StartedSession connected;
    private List<McpToolStore.ToolRecord> catalog = List.of();
    private String connectedFileHash, pendingId, pendingHash, activeTask;

    public InteractiveMcpService(Path workspace, StateStore state) {
        this.workspace=workspace.toAbsolutePath().normalize();this.state=state;
        configFile=this.workspace.resolve(".paicli/mcp.json");runtime=new McpClientRuntime(state);
    }

    public synchronized ShellCommandResult command(List<String> args, InteractiveSession session) {
        try {
            String op=args.isEmpty()?"status":args.getFirst();
            if (!Set.of("status","list","tools").contains(op)
                    && (activeTask!=null || session.snapshot().currentTaskId()!=null))
                throw new IllegalStateException("MCP configuration and connection are frozen while a task is active");
            return switch(op) {
                case "status" -> { exact(args,0,1);yield result(List.of(status())); }
                case "list" -> { exact(args,1,1);yield result(configs().stream().map(s -> s.serverId()+" ("+(s.enabled()?"enabled":"disabled")+")").toList()); }
                case "tools" -> { exact(args,1,1);yield result(catalog.stream().map(t->t.originalName()+" -> "+t.exposedName()+" ["+
                        t.exposureStatus()+(t.rejectionCategory()==null?"":": "+t.rejectionCategory())+"]").toList()); }
                case "config" -> {
                    exact(args,3,3);
                    if(!args.get(1).equals("import")) throw new IllegalArgumentException("usage: /mcp config import <json-file>");
                    Path source=workspace.resolve(args.get(2)).normalize();
                    List<McpServerConfig> incoming=new McpConfigLoader(workspace,true).load(source).servers();
                    List<McpServerConfig> merged=new ArrayList<>(configs());Set<String> ids=new HashSet<>();
                    merged.forEach(s->ids.add(s.serverId()));
                    for(var server:incoming) {
                        if(!ids.add(server.serverId())) throw new IllegalArgumentException("MCP server already configured: "+server.serverId());
                        merged.add(server);
                    }
                    if(merged.size()>32)throw new IllegalArgumentException("at most 32 MCP servers");
                    writeConfiguration(new McpClientConfig(merged));pendingId=null;pendingHash=null;
                    yield result(List.of("Imported "+incoming.size()+" MCP configuration(s). Use /mcp connect <server-id> to review startup."));
                }
                case "connect" -> {
                    exact(args,2,3);
                    if(connected!=null)throw new IllegalStateException("disconnect the current MCP service before connecting");
                    var server=configs().stream().filter(s->s.serverId().equals(args.get(1))).findFirst()
                            .orElseThrow(()->new IllegalArgumentException("unknown MCP server"));
                    if(!server.enabled())throw new IllegalArgumentException("MCP server is disabled");
                    var manifest=new McpConfigManifestBuilder().build(server);
                    if(args.size()==2) {
                        pendingId=server.serverId();pendingHash=manifest.configHash();
                        List<String> lines=new ArrayList<>();
                        lines.add("Start external MCP process: "+server.serverId());
                        lines.add("Executable: "+server.executable());lines.add("Arguments: "+json.writeValueAsString(server.args()));
                        lines.add("Working directory: "+server.workingDirectory());
                        lines.add("Environment references: "+server.environmentReferences().stream().map(McpEnvironmentReference::name).toList());
                        lines.add("Allowed tools: "+server.allowedToolNames());lines.add("Frozen artifacts: "+server.artifactPaths());
                        lines.add("The process runs with your OS permissions. Tool approval does not sandbox it.");
                        lines.add("To confirm: /mcp connect "+server.serverId()+" "+pendingHash);
                        yield result(lines);
                    }
                    boolean confirmed=server.serverId().equals(pendingId) && manifest.configHash().equals(pendingHash)
                            && manifest.configHash().equals(args.get(2));
                    pendingId=null;pendingHash=null;
                    if(!confirmed)throw new IllegalStateException("MCP confirmation expired or startup changed; review /mcp connect again");
                    String fileHash=fileHash();
                    var started=runtime.startTrustedSession(server,"interactive-mcp","connect-"+UUID.randomUUID(),1,System.getenv());
                    if(!started.manifestHash().equals(manifest.configHash()) || !fileHash.equals(fileHash())) {
                        runtime.closeSession(started.sessionId());throw new IllegalStateException("MCP config changed during connection");
                    }
                    selected=server;connected=started;connectedFileHash=fileHash;
                    catalog=state.mcpTools.findAll(started.catalogId());
                    yield result(List.of("Connected: "+server.serverId(),"Tools exposed: "+catalog.stream().filter(t->t.exposureStatus().equals("EXPOSED")).count(),"Use /mcp tools for supported tools and rejection reasons."));
                }
                case "disconnect" -> { exact(args,1,1);closeConnection();yield result(List.of("MCP disconnected.")); }
                default -> throw new IllegalArgumentException("usage: /mcp [status|list|tools|config import <json-file>|connect <server-id> [confirmation]|disconnect]");
            };
        } catch(Exception failure) {
            return ShellCommandResult.continueWith("MCP_ERROR",List.of(new PersistenceSanitizer().sanitizeAndTruncate(
                    failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(),2048)));
        }
    }

    public synchronized AutoCloseable acquireTask(String task, boolean recovery) throws Exception {
        if(activeTask!=null)throw new IllegalStateException("MCP task scope already active");
        validateConnection();
        if(recovery)verifyRecovery(task);
        activeTask=task;
        return ()->{synchronized(this){if(Objects.equals(activeTask,task))activeTask=null;}};
    }

    public synchronized void verifyRecovery(String task) throws Exception {
        validateConnection();
        var old=state.mcpTaskBindings.find(task);
        var expected=binding(task);
        if(old.isPresent()?!old.get().equals(expected):connected!=null)
            throw new IllegalStateException("MCP_RECOVERY_BINDING_MISMATCH: reconnect the original server/config/catalog before recovery");
        if(state.mcpTaskBindings.hasUncertainCalls(task))
            throw new IllegalStateException("MCP_UNKNOWN_OUTCOME: recovery cannot replay an uncertain external call");
    }

    public synchronized ToolRegistry derive(String task, ToolRegistry builtins, ApprovalPolicy policy) throws Exception {
        if(!Objects.equals(activeTask,task))throw new IllegalStateException("MCP task scope missing");
        validateConnection();
        state.mcpTaskBindings.bind(binding(task));
        if(connected==null)return builtins;
        var derived=runtime.deriveToolRegistry(connected.sessionId(),builtins,policy,null,"agentloop");
        for(var tool:derived.allTools()) if(tool instanceof com.paicli.mcp.tool.McpRegisteredTool external)
            external.bindConnectionFence(()->checkTaskConnection(task));
        return derived;
    }

    private synchronized void checkTaskConnection(String task) {
        if(!Objects.equals(activeTask,task))throw new IllegalStateException("MCP task scope expired");
        try { validateConnection(); }
        catch(Exception error) { throw new IllegalStateException("MCP_CONNECTION_STALE",error); }
    }

    private McpTaskBindingStore.Binding binding(String task) {
        return connected==null?new McpTaskBindingStore.Binding(task,"none","none","none"):
                new McpTaskBindingStore.Binding(task,selected.serverId(),connected.manifestHash(),connected.catalogHash());
    }
    private void validateConnection() throws Exception {
        if(connected==null)return;
        if(!runtime.isUsable(connected.sessionId()) || !connectedFileHash.equals(fileHash())
                || !connected.manifestHash().equals(new McpConfigManifestBuilder().build(selected).configHash()))
            throw new IllegalStateException("MCP_CONNECTION_STALE: disconnect and explicitly reconnect");
    }
    private String status() {
        return connected==null?"MCP disconnected":("MCP "+selected.serverId()+": "+
                (runtime.isUsable(connected.sessionId())?"connected":"stale; reconnect required")+
                (activeTask==null?"":" (frozen for task "+activeTask+")"));
    }
    private List<McpServerConfig> configs() throws Exception {
        if(!Files.exists(configFile,LinkOption.NOFOLLOW_LINKS))return List.of();
        return new McpConfigLoader(workspace,true).load(configFile).servers();
    }
    private String fileHash() throws Exception {
        McpArtifactSnapshot.requirePlain(configFile);
        return McpConfigManifestBuilder.hash(Files.readAllBytes(configFile));
    }
    private void writeConfiguration(McpClientConfig config) throws Exception {
        Path parent=configFile.getParent();
        if(Files.exists(parent,LinkOption.NOFOLLOW_LINKS))McpArtifactSnapshot.requirePlain(parent);
        else { McpArtifactSnapshot.requirePlain(workspace);Files.createDirectory(parent); }
        if(Files.exists(configFile,LinkOption.NOFOLLOW_LINKS))McpArtifactSnapshot.requirePlain(configFile);
        Path temporary=Files.createTempFile(parent,"mcp-import-",".json");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),config);
            Files.move(temporary,configFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void exact(List<String> args,int min,int max) {
        if(args.size()<min||args.size()>max)throw new IllegalArgumentException("invalid MCP command arguments");
    }
    private ShellCommandResult result(List<String> lines) {
        var sanitizer=new PersistenceSanitizer();
        return ShellCommandResult.continueWith("MCP",lines.stream().limit(300)
                .map(s->sanitizer.sanitizeAndTruncate(s,8192)).toList());
    }
    private void closeConnection() {
        if(connected!=null)runtime.closeSession(connected.sessionId());
        connected=null;selected=null;catalog=List.of();connectedFileHash=null;pendingId=null;pendingHash=null;
    }
    @Override public synchronized void close() { closeConnection();runtime.close(); }
}
