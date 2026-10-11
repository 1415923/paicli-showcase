package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.safety.SafetyPolicy;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Shared model-facing protected-path classifier, separate from filesystem containment safety. */
final class WorkspaceControlDirectoryPolicy {
    static final String DIRECTORY_NAME=".paicli";
    static final String DISPLAY_NAME=".paicli/";
    private static final Set<String> SECRET_FILE_NAMES=Set.of(
            ".env", ".env.local", ".env.development", ".env.test", ".env.production",
            ".env.development.local", ".env.test.local", ".env.production.local");

    private WorkspaceControlDirectoryPolicy(){ }

    enum Access { READ, LIST, GREP, WRITE }
    enum PathClass { PUBLIC, SECRET, GIT_METADATA, PAICLI_CONTROL }

    static boolean includeInternal(ToolCall call){
        return Boolean.TRUE.equals(call.arguments().get("include_internal"));
    }

    static PathClass classify(SafetyPolicy policy,Path path){
        Path relative=policy.getWorkspaceRoot().relativize(path.toAbsolutePath().normalize());
        if(relative.getNameCount()>0&&SECRET_FILE_NAMES.contains(relative.getFileName()
                .toString().toLowerCase(Locale.ROOT)))return PathClass.SECRET;
        for(Path component:relative){
            String name=component.toString().toLowerCase(Locale.ROOT);
            if(".git".equals(name))return PathClass.GIT_METADATA;
        }
        for(Path component:relative)
            if(DIRECTORY_NAME.equals(component.toString().toLowerCase(Locale.ROOT)))
                return PathClass.PAICLI_CONTROL;
        return PathClass.PUBLIC;
    }

    static ToolResult authorize(ToolCall call,SafetyPolicy policy,Path path,String toolName,
            Access access){
        return switch(classify(policy,path)){
            case PUBLIC->ToolResult.success("");
            case SECRET->ToolResult.error(toolName+": sensitive environment file is protected");
            case GIT_METADATA->ToolResult.error(toolName+": Git metadata path is protected");
            case PAICLI_CONTROL->access!=Access.WRITE&&includeInternal(call)
                    ?ToolResult.success("")
                    :ToolResult.error(toolName+": .paicli internal control directory is excluded");
        };
    }

    static boolean excludeFromReadTraversal(ToolCall call,SafetyPolicy policy,Path path){
        PathClass pathClass=classify(policy,path);
        return pathClass!=PathClass.PUBLIC
                &&(pathClass!=PathClass.PAICLI_CONTROL||!includeInternal(call));
    }

    static String displayName(SafetyPolicy policy,Path path){
        return switch(classify(policy,path)){
            case SECRET->path.getFileName().toString();
            case GIT_METADATA->".git/";
            case PAICLI_CONTROL->DISPLAY_NAME;
            case PUBLIC->"";
        };
    }
}
