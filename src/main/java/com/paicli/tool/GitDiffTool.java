package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.runtime.BoundedProcessExecutor;
import com.paicli.runtime.OwnedProcessRegistry;
import com.paicli.safety.SafetyPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Provides strictly allow-listed, read-only Git diff/status/log information. */
public class GitDiffTool implements Tool {
    private static final String NAME="git_diff";private static final ObjectMapper MAPPER=new ObjectMapper();
    private final SafetyPolicy policy;private final GitDiffArgumentPolicy arguments=new GitDiffArgumentPolicy();private final OwnedProcessRegistry processes;
    public GitDiffTool(SafetyPolicy policy){this(policy,null);}
    public GitDiffTool(SafetyPolicy policy,OwnedProcessRegistry processes){this.policy=policy;this.processes=processes;}
    @Override public String name(){return NAME;}
    @Override public String description(){return "Show allow-listed Git diff, status, or log information without a command shell.";}
    @Override public ToolDefinition toDefinition(){
        ObjectNode schema=MAPPER.createObjectNode();schema.put("type","object");ObjectNode props=schema.putObject("properties");
        props.putObject("subcommand").put("type","string").put("description","Read-only Git subcommand: diff, status, or log");
        ObjectNode args=props.putObject("args");args.put("type","array");args.putObject("items").put("type","string");
        args.put("description","Independent allow-listed options and exact paths after --");
        return new ToolDefinition(NAME,description(),schema);
    }
    @Override public ToolResult preflightCall(ToolCall call){
        try{arguments.buildCommand(call.arguments(),policy);return ToolResult.success("");}
        catch(IllegalArgumentException|com.paicli.safety.SafetyViolationException error){
            return ToolResult.error("git_diff: "+error.getMessage());
        }
    }
    @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call){
        try{
            GitDiffArgumentPolicy.NormalizedGitDiff normalized=
                    arguments.normalize(call.arguments(),policy);
            return Optional.of(ToolAuthorityProjection.exactGitDiff(NAME,
                    normalized.subcommand(),normalized.args()));
        }catch(IllegalArgumentException|com.paicli.safety.SafetyViolationException error){
            return Optional.empty();
        }
    }
    @Override public ToolResult execute(ToolCall call){
        final List<String> command;
        try{command=arguments.buildCommand(call.arguments(),policy);}
        catch(IllegalArgumentException|com.paicli.safety.SafetyViolationException e){return ToolResult.error("git_diff: "+e.getMessage());}
        Path workspace=policy.getWorkspaceRoot();
        List<String> executableCommand=new ArrayList<>(command);
        try{executableCommand.set(0,resolveGitExecutable(workspace));}
        catch(IOException e){return ToolResult.error("git_diff: trusted Git executable not found");}
        ProcessBuilder builder=new ProcessBuilder(executableCommand).directory(workspace.toFile());
        hardenEnvironment(builder.environment());
        // cwd alone does not isolate Git: stop discovery before the parent repository.
        // Workspace-local .git directories and linked-worktree .git files still work.
        try{
            Path parent=workspace.toRealPath().getParent();
            if(parent!=null)builder.environment().put("GIT_CEILING_DIRECTORIES",parent.toString());
        }catch(IOException unavailable){return ToolResult.error("git_diff: workspace unavailable");}
        BoundedProcessExecutor.Result result=new BoundedProcessExecutor(policy.getMaxOutputBytes(),processes)
                .execute(builder,Duration.ofSeconds(policy.getTimeoutSeconds()));
        String output=policy.truncateOutput(result.combinedOutput());
        return switch(result.status()){
            case SUCCEEDED->ToolResult.success(output,Map.of("exitCode",result.exitCode()));
            case NON_ZERO->ToolResult.success(output+"\n[exit code: "+result.exitCode()+"]",Map.of("exitCode",result.exitCode()));
            case TIMED_OUT->ToolResult.error("git_diff: timed out after "+policy.getTimeoutSeconds()+"s");
            case START_FAILED->ToolResult.error("git_diff: process could not start");
            case CAPTURE_FAILED->ToolResult.error("git_diff: output capture failed");
            case INTERRUPTED->ToolResult.error("git_diff: interrupted");
            case CLEANUP_FAILED->ToolResult.error("git_diff: process cleanup failed");
        };
    }
    static String resolveGitExecutable(Path workspace) throws IOException {
        String pathValue=System.getenv("PATH");
        if(pathValue==null||pathValue.isBlank())throw new IOException("PATH unavailable");
        boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows");
        String executable=windows?"git.exe":"git";
        for(String item:pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))){
            String cleaned=item.trim();
            if(cleaned.length()>1&&cleaned.startsWith("\"")&&cleaned.endsWith("\""))cleaned=cleaned.substring(1,cleaned.length()-1);
            if(cleaned.isBlank())continue;
            try{
                Path directory=Path.of(cleaned);
                if(!directory.isAbsolute())continue;
                Path candidate=directory.resolve(executable);
                if(Files.isRegularFile(candidate)){
                    Path real=candidate.toRealPath();
                    if(!real.startsWith(workspace))return real.toString();
                }
            }catch(IOException|RuntimeException ignored){ }
        }
        throw new IOException("Git executable unavailable");
    }
    static void hardenEnvironment(Map<String,String> environment){
        List<String> remove=new ArrayList<>();for(String key:environment.keySet())if(key.toUpperCase(Locale.ROOT).startsWith("GIT_"))remove.add(key);
        remove.forEach(environment::remove);environment.put("GIT_TERMINAL_PROMPT","0");environment.put("GIT_OPTIONAL_LOCKS","0");
        environment.put("GIT_PAGER","cat");environment.put("PAGER","cat");
    }
}
