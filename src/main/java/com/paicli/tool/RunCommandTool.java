package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.runtime.BoundedProcessExecutor;
import com.paicli.runtime.OwnedProcessRegistry;
import com.paicli.safety.SafetyPolicy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Executes a HIGH-risk shell command with approval, timeout, and bounded concurrent output drain. */
public class RunCommandTool implements Tool {
    private static final String NAME="run_command";private static final ObjectMapper MAPPER=new ObjectMapper();
    private final SafetyPolicy policy;private final OwnedProcessRegistry processes;
    public RunCommandTool(SafetyPolicy policy){this(policy,null);}
    public RunCommandTool(SafetyPolicy policy,OwnedProcessRegistry processes){this.policy=policy;this.processes=processes;}
    @Override public String name(){return NAME;}
    @Override public String description(){
        String shell=isWindows()
                ?"Windows command processor (ComSpec, default cmd.exe), with /d /s /c. Use cmd.exe syntax."
                :"/bin/sh -c. Use POSIX shell syntax.";
        return "Execute a shell command in the workspace using "+shell
                +" Quote executable paths and individual arguments separately. Requires high-risk approval.";
    }
    @Override public ToolDefinition toDefinition(){ObjectNode schema=MAPPER.createObjectNode();schema.put("type","object");ObjectNode props=schema.putObject("properties");props.putObject("command").put("type","string").put("description","The shell command to execute");schema.putArray("required").add("command");return new ToolDefinition(NAME,description(),schema);}
    @Override public ToolResult preflightCall(ToolCall call){
        Object value=call.arguments().get("command");
        if(!(value instanceof String command)||command.isBlank())
            return ToolResult.error("run_command: missing or invalid 'command' parameter");
        try{policy.validateCommand(normalizeCommand(command));return ToolResult.success("");}
        catch(com.paicli.safety.SafetyViolationException error){
            return ToolResult.error("run_command: "+error.getMessage());
        }
    }
    @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call){
        Object value=call.arguments().get("command");
        if(!(value instanceof String command)||!preflightCall(call).success())return Optional.empty();
        return Optional.of(ToolAuthorityProjection.exactCommand(NAME,normalizeCommand(command)));
    }
    @Override public ToolResult execute(ToolCall call){
        Object value=call.arguments().get("command");if(!(value instanceof String command)||command.isBlank())return ToolResult.error("run_command: missing or invalid 'command' parameter");
        command=normalizeCommand(command);
        try{policy.validateCommand(command);}catch(com.paicli.safety.SafetyViolationException e){return ToolResult.error("run_command: "+e.getMessage());}
        ProcessBuilder builder=isWindows()?new ProcessBuilder(System.getenv().getOrDefault("ComSpec","cmd.exe"),"/d","/s","/c",command):new ProcessBuilder("/bin/sh","-c",command);
        builder.directory(policy.getWorkspaceRoot().toFile());
        BoundedProcessExecutor.Result result=new BoundedProcessExecutor(policy.getMaxOutputBytes(),processes).execute(builder,Duration.ofSeconds(policy.getTimeoutSeconds()));
        String output=policy.truncateOutput(result.combinedOutput());
        Map<String,Object> metadata=new LinkedHashMap<>();
        if(result.exitCode()!=null)metadata.put("exitCode",result.exitCode());
        metadata.put("commandStatus",result.status().name());
        metadata.put("stdout",transcriptStream(result.stdout()));
        metadata.put("stderr",transcriptStream(result.stderr()));
        metadata.put("stdoutTruncated",result.stdoutTruncated());
        metadata.put("stderrTruncated",result.stderrTruncated());
        return switch(result.status()){
            case SUCCEEDED->ToolResult.success(output,metadata);
            case NON_ZERO->ToolResult.success(output+"\n[exit code: "+result.exitCode()+"]",metadata);
            case TIMED_OUT->ToolResult.error("run_command: timed out after "+policy.getTimeoutSeconds()+"s");
            case START_FAILED->ToolResult.error("run_command: process could not start");
            case CAPTURE_FAILED->ToolResult.error("run_command: output capture failed");
            case INTERRUPTED->ToolResult.error("run_command: interrupted");
            case CLEANUP_FAILED->ToolResult.error("run_command: process cleanup failed");
        };
    }
    private static String normalizeCommand(String command){
        return command.replace("\r\n","\n").replace('\r','\n').strip();
    }
    private String transcriptStream(String value){
        String safe=policy.truncateOutput(value==null?"":value);
        return safe.length()<=4096?safe:safe.substring(0,4096)+"\n... (transcript truncated)";
    }
    private boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows");}
}
