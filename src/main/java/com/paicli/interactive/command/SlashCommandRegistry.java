package com.paicli.interactive.command;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SlashCommandRegistry {
    private final Map<SlashCommand,SlashCommandSpec> specs;
    private final Map<String,SlashCommand> aliases = Map.of("/quit", SlashCommand.EXIT);

    public SlashCommandRegistry(){
        Map<SlashCommand,SlashCommandSpec> m=new EnumMap<>(SlashCommand.class);
        add(m,SlashCommand.HELP,"/help",true,false,false,0,0,"bounded command list","usage error");
        add(m,SlashCommand.MCP,"/mcp [status|list|tools|config import <json-file>|connect <server-id> [confirmation]|disconnect]",
                true,true,false,0,3,"external service configuration, connection and tools","invalid MCP command");
        add(m,SlashCommand.SKILLS,"/skills [list|show <name>|init <name>|import <directory> [sha256]]",
                true,true,false,0,3,"Skill packages, import confirmation and drafts","invalid skills command");
        add(m,SlashCommand.STATUS,"/status [task|provider|usage|--details]",true,false,false,0,1,
                "unified operational, task or provider facts","usage error");
        add(m,SlashCommand.PROVIDER,"/provider [status|set ollama|set openai|test|clear|credential status|login|logout]",true,true,false,0,2,"provider onboarding, credentials and status","invalid provider command");
        add(m,SlashCommand.MODEL,"/model [list|set <model-id>]",true,true,false,0,2,"current, discovered or selected model","invalid model command");
        add(m,SlashCommand.MODE,"/mode [plan|read-only|on-request|workspace-write]",true,true,false,0,1,"current or selected approval mode","invalid mode");
        add(m,SlashCommand.PLAN,"/plan [--details]",true,false,false,0,1,
                "current/last Plan checklist or full immutable revision","no plan available");
        add(m,SlashCommand.DIFF,"/diff",true,false,false,0,0,"current/last bounded diff","no diff available");
        add(m,SlashCommand.TASKS,"/tasks [limit|active|recent|failed|task-id]",true,false,false,0,1,"bounded filtered task list","invalid selector");
        add(m,SlashCommand.SESSIONS,"/sessions",true,false,false,0,0,"Saved Sessions in this workspace","session list unavailable");
        add(m,SlashCommand.RESUME,"/resume <thread|--last>",false,true,true,0,1,"bind a Saved Session without Task Recovery","Saved Session not found");
        add(m,SlashCommand.NEW,"/new",false,true,false,0,0,"clear the Saved Session binding","active task blocks new");
        add(m,SlashCommand.CONTINUE,"/continue <instruction>",false,true,false,1,64,
                "create a Child Task from an eligible completed Thread head","Follow-up not eligible");
        add(m,SlashCommand.TASK,"/task recover [task-id]",false,true,true,1,2,"recover the bound Thread Head Task","Task not recoverable");
        add(m,SlashCommand.REWIND,"/rewind [--details] [snapshotId]",false,true,true,0,2,"rewind selector/admission/result","snapshot not rewindable");
        add(m,SlashCommand.REPORT,"/report [latest|taskId]",true,false,false,0,1,"bounded current/latest/task report","report unavailable");
        add(m,SlashCommand.COMPACT,"/compact [status]",false,true,false,0,1,
                "compact the shell-bound terminal Task context or show status","compaction unavailable");
        add(m,SlashCommand.VERIFY,"/verify [status|auto|off] [--save project|user]",true,true,false,0,3,"structured verification policy","invalid verification mode");
        add(m,SlashCommand.VERBOSE,"/verbose [status|on|off]",true,true,false,0,1,"terminal event detail level","invalid verbose mode");
        add(m,SlashCommand.DOCTOR,"/doctor",true,false,false,0,0,"read-only diagnostics","diagnostic failure");
        add(m,SlashCommand.HISTORY,"/history [limit|clear]",true,true,false,0,1,
                "bounded recent input or persistent history clear","invalid history command");
        add(m,SlashCommand.CLEAR,"/clear",false,true,false,0,0,"new context generation","active task blocks clear");
        add(m,SlashCommand.EXIT,"/exit",false,true,false,0,0,"session closed","active task requires cancellation");
        specs=Map.copyOf(m);
    }

    public List<SlashCommandSpec> all(){return specs.values().stream().sorted((a,b)->a.command().token().compareTo(b.command().token())).toList();}
    public Optional<SlashCommandSpec> find(SlashCommand command){return Optional.ofNullable(specs.get(command));}
    public Optional<SlashCommand> resolveToken(String token) {
        Optional<SlashCommand> canonical = specs.keySet().stream()
                .filter(command -> command.token().equals(token)).findFirst();
        return canonical.isPresent() ? canonical : Optional.ofNullable(aliases.get(token));
    }
    public Set<String> tokensFor(SlashCommand command) {
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        tokens.add(command.token());
        aliases.forEach((token, target) -> {
            if (target == command) tokens.add(token);
        });
        return Set.copyOf(tokens);
    }
    private static void add(Map<SlashCommand,SlashCommandSpec> m,SlashCommand c,String s,boolean running,
            boolean mutates,boolean selector,int min,int max,String output,String error){m.put(c,new SlashCommandSpec(c,s,running,mutates,selector,min,max,output,error));}
}
