package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.safety.SafetyPolicy;
import com.paicli.agent.ToolFactProjection;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lists a workspace directory without following links. */
public class ListFilesTool implements Tool {
    private static final String NAME="list_files"; private static final ObjectMapper MAPPER=new ObjectMapper();
    private final SafetyPolicy policy;
    public ListFilesTool(SafetyPolicy policy){this.policy=policy;}
    @Override public String name(){return NAME;}
    @Override public String description(){return "List files and directories at one workspace-relative path without using a shell. Prefer this dedicated read-only Tool for directory enumeration; list returned subdirectories separately when recursion is needed. PaiCLI's .paicli control directory is excluded unless the user explicitly requests internal state.";}
    @Override public ToolDefinition toDefinition(){
        ObjectNode schema=MAPPER.createObjectNode();schema.put("type","object");
        ObjectNode props=schema.putObject("properties");
        props.putObject("path").put("type","string").put("description","Relative path to list (e.g. '.' or 'src/main')");
        props.putObject("include_internal").put("type","boolean").put("description",
                "Include PaiCLI's .paicli control directory only when explicitly requested");
        schema.putArray("required").add("path"); return new ToolDefinition(NAME,description(),schema);
    }
    @Override public ToolResult preflightCall(ToolCall call){
        Object value=call.arguments().get("path");
        if(!(value instanceof String pathStr)||pathStr.isBlank())
            return ToolResult.error("list_files: missing or invalid 'path' parameter");
        try{
            Path resolved=policy.resolveWorkspacePath(Path.of(pathStr));
            ToolResult control=WorkspaceControlDirectoryPolicy.authorize(call,policy,resolved,NAME,
                    WorkspaceControlDirectoryPolicy.Access.LIST);
            if(!control.success())return control;
            return ToolResult.success("");
        }catch(com.paicli.safety.SafetyViolationException|RuntimeException error){
            return ToolResult.error("list_files: unsafe path");
        }
    }
    @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call){
        Object value=call.arguments().get("path");
        if(!(value instanceof String pathStr)||pathStr.isBlank())return Optional.empty();
        try{
            Path resolved=policy.resolveWorkspacePath(Path.of(pathStr));
            ToolResult control=WorkspaceControlDirectoryPolicy.authorize(call,policy,resolved,NAME,
                    WorkspaceControlDirectoryPolicy.Access.LIST);
            if(!control.success())return Optional.empty();
            return Optional.of(ToolAuthorityProjection.exactPath(NAME,
                    com.paicli.state.PlanCompletionModels.EffectClass.READ,
                    ToolAuthorityProjection.normalizedWorkspacePath(policy,resolved),null,
                    WorkspaceControlDirectoryPolicy.includeInternal(call),null));
        }catch(com.paicli.safety.SafetyViolationException|RuntimeException error){
            return Optional.empty();
        }
    }
    @Override public ToolResult execute(ToolCall call){
        Map<String,Object> args=call.arguments();Object value=args.get("path");
        if(!(value instanceof String pathStr))return ToolResult.error("list_files: missing or invalid 'path' parameter");
        try{
            Path resolved=policy.resolveWorkspacePath(Path.of(pathStr));
            ToolResult control=WorkspaceControlDirectoryPolicy.authorize(call,policy,resolved,NAME,
                    WorkspaceControlDirectoryPolicy.Access.LIST);
            if(!control.success())return control;
            if(!Files.isDirectory(resolved,LinkOption.NOFOLLOW_LINKS))return ToolResult.error("list_files: not a directory: "+pathStr);
            List<Path> entries=new ArrayList<>();
            int excluded=0;
            java.util.Set<String> excludedNames=new java.util.LinkedHashSet<>();
            try(DirectoryStream<Path> stream=Files.newDirectoryStream(resolved)){for(Path entry:stream){
                if(WorkspaceControlDirectoryPolicy.excludeFromReadTraversal(call,policy,entry)){
                    excluded++;
                    excludedNames.add(WorkspaceControlDirectoryPolicy.displayName(policy,entry));
                    continue;
                }
                entries.add(entry);
            }}
            entries.sort(Comparator.comparing(entry->entry.getFileName().toString()));
            StringBuilder output=new StringBuilder();
            for(Path entry:entries){
                String name=entry.getFileName().toString();
                output.append(name);
                if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))output.append('/');
                output.append('\n');
                if(output.length()>policy.getMaxOutputBytes()*2L)break;
            }
            Map<String,Object> metadata=new java.util.LinkedHashMap<>();
            metadata.put("entryCount",entries.size());
            metadata.put(ToolFactProjection.VISIBLE_ENTRY_COUNT,entries.size());
            metadata.put(ToolFactProjection.EXCLUDED_CONTROL_COUNT,excluded);
            metadata.put(ToolFactProjection.CONTROL_DIRECTORY_POLICY_APPLIED,
                    !WorkspaceControlDirectoryPolicy.includeInternal(call));
            if(excluded>0)metadata.put(ToolFactProjection.EXCLUDED_CONTROLS,
                    String.join(",",excludedNames));
            return ToolResult.success(policy.truncateOutput(output.toString()),metadata);
        }catch(com.paicli.safety.SafetyViolationException e){return ToolResult.error("list_files: "+e.getMessage());}
        catch(IOException|RuntimeException e){return ToolResult.error("list_files: I/O error: "+safeMessage(e));}
    }
    private String safeMessage(Throwable e){return e.getMessage()==null||e.getMessage().isBlank()?e.getClass().getSimpleName():e.getMessage();}
}
