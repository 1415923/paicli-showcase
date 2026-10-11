package com.paicli.interactive.command;
import com.paicli.interactive.session.InteractiveSession;
import java.util.List;
@FunctionalInterface
public interface InteractiveCompactionCommandPort {
    ShellCommandResult compact(List<String> arguments,InteractiveSession.Snapshot session);
    static InteractiveCompactionCommandPort unavailable(){return (a,s)->ShellCommandResult.continueWith(
            "COMPACTION_UNAVAILABLE",List.of("Compaction is unavailable."));}
}
