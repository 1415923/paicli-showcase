package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.safety.SafetyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import com.paicli.worktree.GitTestSupport;

class GitDiffToolSecurityIT {
    @TempDir Path workspace;
    GitDiffTool tool;

    @BeforeEach
    void setUp() throws Exception {
        runGit("init");
        tool = new GitDiffTool(new SafetyPolicy(workspace, 15, 4096));
    }

    @AfterEach
    void clearGitReadOnlyAttributes() throws Exception {
        GitTestSupport.cleanup(workspace, workspace);
    }
    @Test
    void nestedNonRepositoryCannotReadParentStatusOrLog() throws Exception {
        Files.writeString(workspace.resolve("PARENT_PRIVATE_MARKER.txt"),"private parent\n");
        Path child=Files.createDirectories(workspace.resolve("fixture"));
        var nested=new GitDiffTool(new SafetyPolicy(child,15,4096));
        for(String subcommand:List.of("status","diff","log")){
            var result=nested.execute(new ToolCall("git_diff",Map.of("subcommand",subcommand)));
            assertFalse(String.valueOf(result.output()).contains("PARENT_PRIVATE_MARKER"));
            assertNotEquals(0,result.metadata().get("exitCode"),"must not discover parent repository");
        }
        runGit("-C",child.toString(),"init");
        Files.writeString(child.resolve("child.txt"),"child\n");
        var allowed=nested.execute(new ToolCall("git_diff",Map.of("subcommand","status")));
        assertEquals(0,allowed.metadata().get("exitCode"));
        assertTrue(allowed.output().contains("child.txt"));
        assertFalse(allowed.output().contains("PARENT_PRIVATE_MARKER"));
    }

    @Test
    void workspaceRootCanBeALinkedWorktree() throws Exception {
        runGit("-c","user.name=Test","-c","user.email=test@example.invalid",
                "commit","--allow-empty","--no-gpg-sign","-m","fixture baseline");
        Path linked=workspace.resolve("linked");
        runGit("worktree","add","--detach",linked.toString(),"HEAD");
        Files.writeString(linked.resolve("linked-only.txt"),"linked\n");
        var result=new GitDiffTool(new SafetyPolicy(linked,15,4096)).execute(
                new ToolCall("git_diff",Map.of("subcommand","status")));
        assertEquals(0,result.metadata().get("exitCode"));
        assertTrue(result.output().contains("linked-only.txt"));
    }

    @Test
    void resolvesTrustedGitOutsideWorkspaceAndScrubsGitEnvironment() throws Exception {
        Files.writeString(workspace.resolve(isWindows()?"git.exe":"git"), "not executable");
        Path executable=Path.of(GitDiffTool.resolveGitExecutable(workspace));
        assertTrue(executable.isAbsolute());
        assertFalse(executable.startsWith(workspace));

        Map<String,String> environment=new java.util.HashMap<>();
        environment.put("GIT_EXTERNAL_DIFF","escaped-command");
        environment.put("GIT_CONFIG_COUNT","1");
        environment.put("PATH","trusted-path");
        GitDiffTool.hardenEnvironment(environment);
        assertFalse(environment.containsKey("GIT_EXTERNAL_DIFF"));
        assertFalse(environment.containsKey("GIT_CONFIG_COUNT"));
        assertEquals("0",environment.get("GIT_OPTIONAL_LOCKS"));
        assertEquals("trusted-path",environment.get("PATH"));
    }
    @Test
    void schemaRequiresStructuredArgumentArray() {
        var args = tool.toDefinition().parameterSchema().path("properties").path("args");
        assertEquals("array", args.path("type").asText());
        assertEquals("string", args.path("items").path("type").asText());
        assertFalse(tool.execute(new ToolCall("git_diff", Map.of("args", "--stat & whoami"))).success());
        assertFalse(tool.execute(new ToolCall("git_diff", Map.of("command", "whoami"))).success());
    }

    @Test
    void rejectsWindowsAndUnixShellInjectionPayloadsBeforeLaunch() {
        List<String> payloads = List.of(
                "; touch escaped", "&& echo escaped", "| cat secret", "$(whoami)",
                "`whoami`", "& type secret.txt", "%COMSPEC% /c whoami", "line\nwhoami");
        for (String payload : payloads) {
            var result = tool.execute(new ToolCall("git_diff",
                    Map.of("subcommand", "diff", "args", List.of("--", payload))));
            assertFalse(result.success(), payload);
            assertTrue(result.error().contains("forbidden"), result.error());
        }
    }

    @Test
    void rejectsSideEffectAndConfigurationOptions() {
        List<String> blocked = List.of("--output=escaped.txt", "--ext-diff", "--textconv",
                "--no-index", "-c", "--config-env=x=y", "--git-dir=..", "--work-tree=..");
        for (String option : blocked) {
            var result = tool.execute(new ToolCall("git_diff",
                    Map.of("subcommand", "diff", "args", List.of(option))));
            assertFalse(result.success(), option);
            assertFalse(Files.exists(workspace.resolve("escaped.txt")));
        }
    }

    @Test
    void allowsOnlyValidatedExactPathspecs() throws Exception {
        Files.writeString(workspace.resolve("safe file.txt"), "one\n");
        runGit("add", "safe file.txt");
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--no-gpg-sign", "-m", "baseline");
        Files.writeString(workspace.resolve("safe file.txt"), "two\n");

        var result = tool.execute(new ToolCall("git_diff", Map.of("subcommand", "diff",
                "args", List.of("--no-color", "--", "safe file.txt"))));
        assertTrue(result.success(), result.error());
        assertTrue(result.output().contains("two"));
        assertFalse(tool.execute(new ToolCall("git_diff", Map.of("subcommand", "diff",
                "args", List.of("--", ":(top)../outside")))).success());
    }

    @Test
    void largeDiffIsDrainedAndBoundedInsteadOfTimingOut() throws Exception {
        Files.writeString(workspace.resolve("large.txt"), lines("before"));
        runGit("add", "large.txt");
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--no-gpg-sign", "-m", "baseline");
        Files.writeString(workspace.resolve("large.txt"), lines("after"));

        var result = tool.execute(new ToolCall("git_diff", Map.of("subcommand", "diff",
                "args", List.of("--no-color", "--", "large.txt"))));
        assertTrue(result.success(), result.error());
        assertTrue(result.output().contains("truncated"));
        assertTrue(result.output().length() < 4400);
    }

    private String lines(String prefix) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 20_000; i++) value.append(prefix).append('-').append(i).append('\n');
        return value.toString();
    }

    private boolean isWindows(){return System.getProperty("os.name","").toLowerCase().contains("windows");}

    private void runGit(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(); command.add("git"); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "git fixture timed out");
        byte[] output; try (var input=process.getInputStream()) { output=input.readAllBytes(); }
        assertEquals(0, process.exitValue(), new String(output));
    }
}
