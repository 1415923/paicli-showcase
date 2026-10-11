package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.safety.SafetyPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Reads a text file within the workspace without following links. */
public class ReadFileTool implements Tool {
    private static final String NAME = "read_file";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SafetyPolicy policy;

    public ReadFileTool(SafetyPolicy policy) { this.policy = policy; }
    @Override public String name() { return NAME; }
    @Override public String description() { return "Read the contents of a workspace-relative text file without using a shell. Set line_numbers=true when locating code for citations; otherwise returns plain text."; }

    @Override public ToolDefinition toDefinition() {
        ObjectNode schema = MAPPER.createObjectNode(); schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Relative path to the file to read");
        props.putObject("max_lines").put("type", "integer").put("description", "Maximum number of lines to read (optional)");
        props.putObject("line_numbers").put("type", "boolean").put("description", "Prefix each line with its 1-based file line number (default false; prefixes are not file content)");
        props.putObject("include_internal").put("type","boolean").put("description",
                "Read PaiCLI's .paicli control directory only when explicitly requested");
        schema.putArray("required").add("path");
        return new ToolDefinition(NAME, description(), schema);
    }

    @Override public ToolResult preflightCall(ToolCall call) {
        Object value=call.arguments().get("path");
        if(!(value instanceof String pathStr)||pathStr.isBlank())
            return ToolResult.error("read_file: missing or invalid 'path' parameter");
        if(call.arguments().containsKey("line_numbers") && !(call.arguments().get("line_numbers") instanceof Boolean))
            return ToolResult.error("read_file: 'line_numbers' must be a boolean");
        try{
            Path target=policy.resolveWorkspacePath(Path.of(pathStr));
            return WorkspaceControlDirectoryPolicy.authorize(call,policy,target,NAME,
                    WorkspaceControlDirectoryPolicy.Access.READ);
        }catch(com.paicli.safety.SafetyViolationException|RuntimeException error){
            return ToolResult.error("read_file: unsafe path");
        }
    }

    @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call) {
        Object value=call.arguments().get("path");
        if(!(value instanceof String pathStr)||pathStr.isBlank())return Optional.empty();
        try{
            Path target=policy.resolveWorkspacePath(Path.of(pathStr));
            ToolResult control=WorkspaceControlDirectoryPolicy.authorize(call,policy,target,NAME,
                    WorkspaceControlDirectoryPolicy.Access.READ);
            if(!control.success())return Optional.empty();
            Integer maxLines=call.arguments().get("max_lines") instanceof Number number
                    &&number.intValue()>0?number.intValue():null;
            return Optional.of(ToolAuthorityProjection.exactPath(NAME,
                    com.paicli.state.PlanCompletionModels.EffectClass.READ,
                    ToolAuthorityProjection.normalizedWorkspacePath(policy,target),maxLines,
                    WorkspaceControlDirectoryPolicy.includeInternal(call),null));
        }catch(com.paicli.safety.SafetyViolationException|RuntimeException error){
            return Optional.empty();
        }
    }

    @Override public ToolResult execute(ToolCall call) {
        Map<String,Object> args=call.arguments(); Object value=args.get("path");
        if(!(value instanceof String pathStr)) return ToolResult.error("read_file: missing or invalid 'path' parameter");
        if(args.containsKey("line_numbers") && !(args.get("line_numbers") instanceof Boolean))
            return ToolResult.error("read_file: 'line_numbers' must be a boolean");
        boolean numbered=Boolean.TRUE.equals(args.get("line_numbers"));
        int maxLines=-1; if(args.get("max_lines") instanceof Number n) maxLines=n.intValue();
        try {
            Path target=policy.resolveWorkspacePath(Path.of(pathStr));
            ToolResult control=WorkspaceControlDirectoryPolicy.authorize(call,policy,target,NAME,
                    WorkspaceControlDirectoryPolicy.Access.READ);
            if(!control.success())return control;
            if(!java.nio.file.Files.exists(target,java.nio.file.LinkOption.NOFOLLOW_LINKS)) return ToolResult.error("read_file: file not found: "+pathStr);
            try (var channel=policy.openFileForRead(target);
             var reader=new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))) {
            StringBuilder content=new StringBuilder(); String line; int count=0;
            while((line=reader.readLine())!=null && (maxLines<=0 || count<maxLines)) {
                if(count++>0) content.append('\n');
                if(numbered)content.append(count).append(": ");
                content.append(line);
                if(content.length()>policy.getMaxOutputBytes()*2L) break;
            }
            String bounded = policy.truncateOutput(content.toString());
            return ToolResult.success(bounded, Map.of(
                    "relativePath", pathStr.replace('\\', '/'),
                    "bytesRead", bounded.getBytes(StandardCharsets.UTF_8).length));
            }
        } catch (com.paicli.safety.SafetyViolationException e) {
            return ToolResult.error("read_file: "+e.getMessage());
        } catch (IOException | RuntimeException e) {
            return ToolResult.error("read_file: I/O error: "+safeMessage(e));
        }
    }

    private String safeMessage(Throwable error) {
        return error.getMessage()==null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }
}
