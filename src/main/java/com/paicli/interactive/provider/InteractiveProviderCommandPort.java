package com.paicli.interactive.provider;

import com.paicli.interactive.command.ShellCommandResult;
import com.paicli.interactive.session.InteractiveSession;

import java.util.List;

public interface InteractiveProviderCommandPort {
    ShellCommandResult provider(List<String> arguments, InteractiveSession session);
    ShellCommandResult model(List<String> arguments, InteractiveSession session);

    static InteractiveProviderCommandPort unavailable() {
        return new InteractiveProviderCommandPort() {
            public ShellCommandResult provider(List<String> arguments, InteractiveSession session) {
                return ShellCommandResult.continueWith("PROVIDER_UNAVAILABLE",
                        List.of("Provider onboarding is unavailable in this context."));
            }
            public ShellCommandResult model(List<String> arguments, InteractiveSession session) {
                return ShellCommandResult.continueWith("MODEL_UNAVAILABLE",
                        List.of("Model selection is unavailable in this context."));
            }
        };
    }
}
