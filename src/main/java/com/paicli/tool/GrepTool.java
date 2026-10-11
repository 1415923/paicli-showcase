package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.safety.SafetyPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Searches workspace text files without following links. */
public class GrepTool implements Tool {
    private static final String NAME="grep";private static final ObjectMapper MAPPER=new ObjectMapper();
    private final SafetyPolicy policy;
    public GrepTool(SafetyPolicy policy){this.policy=policy;}
    @Override public String name(){return NAME;}
    @Override public String description(){return "Search workspace file contents without using a shell. Prefer this dedicated read-only Tool for text search; returns matching lines.";}
    @Override public ToolDefinition toDefinition(){
        ObjectNode schema=MAPPER.createObjectNode();schema.put("type","object");ObjectNode props=schema.putObject("properties");
        props.putObject("pattern").put("type","string").put("description","Search pattern (regex)");
        props.putObject("path").put("type","string").put("description","Directory or file to search in (default: workspace root)");
        props.putObject("include_internal").put("type","boolean").put("description",
                "Search PaiCLI's .paicli control directory only when explicitly requested");
        schema.putArray("required").add("pattern");return new ToolDefinition(NAME,description(),schema);
    }
    @Override public ToolResult preflightCall(ToolCall call){
        Object pattern=call.arguments().get("pattern");
        if(!(pattern instanceof String text)||text.isBlank())
            return ToolResult.error("grep: missing or invalid 'pattern' parameter");
        try{Pattern.compile(text);}catch(PatternSyntaxException error){
            return ToolResult.error("grep: invalid regex pattern");
        }
        Object path=call.arguments().getOrDefault("path",".");
        if(!(path instanceof String value))return ToolResult.error("grep: invalid path");
        try{
            Path resolved=policy.resolveWorkspacePath(Path.of(value));
            return WorkspaceControlDirectoryPolicy.authorize(call,policy,resolved,NAME,
                    WorkspaceControlDirectoryPolicy.Access.GREP);
        }
        catch(com.paicli.safety.SafetyViolationException|RuntimeException error){
            return ToolResult.error("grep: unsafe path");
        }
    }
    @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call){
        Object pattern=call.arguments().get("pattern");
        if(!(pattern instanceof String text)||text.isBlank())return Optional.empty();
        try{Pattern.compile(text);}catch(PatternSyntaxException error){return Optional.empty();}
        Object path=call.arguments().getOrDefault("path",".");
        if(!(path instanceof String value))return Optional.empty();
        try{
            Path resolved=policy.resolveWorkspacePath(Path.of(value));
            ToolResult control=WorkspaceControlDirectoryPolicy.authorize(call,policy,resolved,NAME,
                    WorkspaceControlDirectoryPolicy.Access.GREP);
            if(!control.success())return Optional.empty();
            return Optional.of(ToolAuthorityProjection.exactGrep(NAME,
                    ToolAuthorityProjection.normalizedWorkspacePath(policy,resolved),text,
                    WorkspaceControlDirectoryPolicy.includeInternal(call)));
        }catch(com.paicli.safety.SafetyViolationException|RuntimeException error){
            return Optional.empty();
        }
    }
    @Override public ToolResult execute(ToolCall call){
        Map<String,Object> args=call.arguments();Object patternValue=args.get("pattern");
        if(!(patternValue instanceof String patternStr)||patternStr.isBlank())return ToolResult.error("grep: missing or invalid 'pattern' parameter");
        String searchPath=args.get("path") instanceof String p?p:".";Pattern regex;
        try{regex=Pattern.compile(patternStr);}catch(PatternSyntaxException e){return ToolResult.error("grep: invalid regex pattern");}
        try{
            Path resolved=policy.resolveWorkspacePath(Path.of(searchPath));List<Path> targets=new ArrayList<>();
            ToolResult control=WorkspaceControlDirectoryPolicy.authorize(call,policy,resolved,NAME,
                    WorkspaceControlDirectoryPolicy.Access.GREP);
            if(!control.success())return control;
            if(policy.isSafeRegularFile(resolved))targets.add(resolved);
            else if(Files.isDirectory(resolved,LinkOption.NOFOLLOW_LINKS)){
                Files.walkFileTree(resolved,new SimpleFileVisitor<>(){
                    @Override public FileVisitResult preVisitDirectory(Path directory,
                            BasicFileAttributes attributes){
                        if(WorkspaceControlDirectoryPolicy.excludeFromReadTraversal(
                                call,policy,directory))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file,
                            BasicFileAttributes attributes){
                        if(!WorkspaceControlDirectoryPolicy.excludeFromReadTraversal(
                                call,policy,file)&&policy.isSafeRegularFile(file))targets.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file,IOException error){
                        return FileVisitResult.CONTINUE;
                    }
                });
                targets.sort(Path::compareTo);
            }else return ToolResult.error("grep: path does not exist or is unsafe: "+searchPath);
            StringBuilder output=new StringBuilder();int matches=0;
            for(Path file:targets){
                if(!isTextFile(file))continue;
                try(var channel=policy.openFileForRead(file);var reader=new BufferedReader(Channels.newReader(channel,StandardCharsets.UTF_8))){
                    String line;int number=0;while((line=reader.readLine())!=null){number++;if(regex.matcher(line).find()){
                        String relative=policy.getWorkspaceRoot().relativize(file).toString();
                        output.append(relative).append(':').append(number).append(": ").append(line).append('\n');matches++;
                        if(output.length()>policy.getMaxOutputBytes()*2L)break;
                    }}
                }
                if(output.length()>policy.getMaxOutputBytes()*2L)break;
            }
            return ToolResult.success(policy.truncateOutput(output.toString()),Map.of("matchCount",matches));
        }catch(com.paicli.safety.SafetyViolationException e){return ToolResult.error("grep: "+e.getMessage());}
        catch(IOException|RuntimeException e){return ToolResult.error("grep: I/O error: "+safeMessage(e));}
    }
    private boolean isTextFile(Path file){
        try(var channel=policy.openFileForRead(file)){ByteBuffer buffer=ByteBuffer.allocate(512);int read=channel.read(buffer);byte[] bytes=buffer.array();for(int i=0;i<read;i++)if(bytes[i]==0)return false;return true;}
        catch(IOException|com.paicli.safety.SafetyViolationException e){return false;}
    }
    private String safeMessage(Throwable e){return e.getMessage()==null||e.getMessage().isBlank()?e.getClass().getSimpleName():e.getMessage();}
}
