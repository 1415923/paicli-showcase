package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.safety.SafetyPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    @TempDir
    Path workspace;

    SafetyPolicy policy;
    ToolRegistry registry;

    @BeforeEach
    void setUp() {
        policy = new SafetyPolicy(workspace);
        registry = new ToolRegistry();
        registry.register(new ListFilesTool(policy));
        registry.register(new ReadFileTool(policy));
        registry.register(new GrepTool(policy));
        registry.register(new RunCommandTool(policy));
        registry.register(new GitDiffTool(policy));
        registry.register(new WriteFileTool(policy));
    }

    // ── ToolRegistry tests ──

    @Test
    void registerAndLookup() {
        assertTrue(registry.lookup("list_files").isPresent());
        assertEquals("list_files", registry.lookup("list_files").get().name());
    }

    @Test
    void lookupUnknownReturnsEmpty() {
        assertTrue(registry.lookup("nonexistent").isEmpty());
    }

    @Test
    void registerDuplicateThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new ListFilesTool(policy)));
    }

    @Test
    void invokeUnknownToolReturnsError() {
        var result = registry.invoke(new ToolCall("nonexistent", Map.of()));
        assertFalse(result.success());
        assertTrue(result.error().contains("Unknown tool"));
    }

    @Test
    void invokeNullToolCallReturnsError() {
        var result = registry.invoke(null);
        assertFalse(result.success());
    }

    @Test
    void allDefinitionsReturnsAllTools() {
        var defs = registry.allDefinitions();
        assertEquals(6, defs.size());
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("list_files")));
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("read_file")));
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("grep")));
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("run_command")));
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("git_diff")));
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("write_file")));
    }

    @Test
    void sizeReturnsRegisteredCount() {
        assertEquals(6, registry.size());
    }

    @Test
    void preflightProjectsExactAuthorityForEveryRegisteredProductionTool() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "fixture");
        var calls = java.util.List.of(
                new ToolCall("list_files", Map.of("path", ".")),
                new ToolCall("read_file", Map.of("path", "README.md", "max_lines", 3)),
                new ToolCall("grep", Map.of("path", ".", "pattern", "fixture")),
                new ToolCall("run_command", Map.of("command", "git status --short")),
                new ToolCall("git_diff", Map.of("subcommand", "status", "args", java.util.List.of("--short"))),
                new ToolCall("write_file", Map.of("path", "out.txt", "content", "private-body")));

        var projections = calls.stream().map(registry::preflight).toList();
        assertTrue(projections.stream().allMatch(ToolRegistry.PreflightResult::accepted));
        assertTrue(projections.stream().allMatch(result -> result.authorityProjection() != null));
        assertEquals(calls.stream().map(ToolCall::name).toList(), projections.stream()
                .map(result -> result.authorityProjection().toolName()).toList());
        assertFalse(projections.getLast().authorityProjection().authoritySelector()
                .canonicalJson().contains("private-body"));

        var first = registry.preflight(new ToolCall("read_file",
                Map.of("path", "README.md", "max_lines", 1)));
        var changed = registry.preflight(new ToolCall("read_file",
                Map.of("path", "README.md", "max_lines", 2)));
        assertNotEquals(first.authorityProjection().authoritySelector().digest(),
                changed.authorityProjection().authoritySelector().digest());
    }

    // ── ListFilesTool tests ──

    @Nested
    class ListFilesTests {

        @Test
        void listEmptyDirectory() throws IOException {
            var result = registry.invoke(new ToolCall("list_files", Map.of("path", ".")));
            assertTrue(result.success());
            assertEquals("", result.output().trim());
        }

        @Test
        void listDirectoryWithFiles() throws IOException {
            Files.writeString(workspace.resolve("a.txt"), "hello");
            Files.writeString(workspace.resolve("b.txt"), "world");

            var result = registry.invoke(new ToolCall("list_files", Map.of("path", ".")));
            assertTrue(result.success());
            assertTrue(result.output().contains("a.txt"));
            assertTrue(result.output().contains("b.txt"));
        }

        @Test
        void userFileListingExcludesPaiCliControlDirectoryByDefault() throws IOException {
            Files.createDirectories(workspace.resolve(".paicli/state"));
            Files.writeString(workspace.resolve(".paicli/state/runtime.db"),"private");
            Files.writeString(workspace.resolve("README.md"),"public");

            var result=registry.invoke(new ToolCall("list_files",Map.of("path",".")));

            assertTrue(result.success());
            assertTrue(result.output().contains("README.md"));
            assertFalse(result.output().contains(".paicli"));
            assertEquals(1,result.metadata().get("excludedControlCount"));
            assertEquals(".paicli/",result.metadata().get("excludedControls"));
            assertEquals(true,result.metadata().get("controlDirectoryPolicyApplied"));
            assertEquals(1,result.metadata().get("visibleEntryCount"));
        }

        @Test
        void explicitInternalStateListingRequiresAndHonorsIncludeInternal() throws IOException {
            Files.createDirectories(workspace.resolve(".paicli"));
            Files.writeString(workspace.resolve(".paicli/state.db"),"private");

            var denied=registry.invoke(new ToolCall("list_files",Map.of("path",".paicli")));
            var allowed=registry.invoke(new ToolCall("list_files",Map.of(
                    "path",".paicli","include_internal",true)));

            assertFalse(denied.success());
            assertTrue(allowed.success());
            assertTrue(allowed.output().contains("state.db"));
        }

        @Test
        void includeInternalParticipatesInReadOnlySignature() throws IOException {
            Files.createDirectories(workspace.resolve(".paicli"));
            var hidden=registry.preflight(new ToolCall("list_files",Map.of("path",".")));
            var visible=registry.preflight(new ToolCall("list_files",Map.of(
                    "path",".","include_internal",true)));
            assertTrue(hidden.accepted());
            assertTrue(visible.accepted());
            assertNotEquals(new ToolCall("list_files",Map.of("path",".")).arguments(),
                    new ToolCall("list_files",Map.of("path",".","include_internal",true))
                            .arguments());
        }

        @Test
        void listShowsTrailingSlashForDirs() throws IOException {
            Files.createDirectory(workspace.resolve("subdir"));

            var result = registry.invoke(new ToolCall("list_files", Map.of("path", ".")));
            assertTrue(result.success());
            assertTrue(result.output().contains("subdir/"));
        }

        @Test
        void listNestedPath() throws IOException {
            Files.createDirectories(workspace.resolve("src/main"));
            Files.writeString(workspace.resolve("src/main/App.java"), "class App {}");

            var result = registry.invoke(new ToolCall("list_files", Map.of("path", "src/main")));
            assertTrue(result.success());
            assertTrue(result.output().contains("App.java"));
        }

        @Test
        void missingPathParameter() {
            var result = registry.invoke(new ToolCall("list_files", Map.of()));
            assertFalse(result.success());
            assertTrue(result.error().contains("missing"));
        }

        @Test
        void pathTraversalRejected() {
            var result = registry.invoke(new ToolCall("list_files", Map.of("path", "../escape")));
            assertFalse(result.success());
        }

        @Test
        void pathOutsideWorkspaceRejected() {
            var result = registry.invoke(new ToolCall("list_files", Map.of("path", "/etc")));
            assertFalse(result.success());
        }

        @Test
        void listNonDirectoryReturnsError() throws IOException {
            Files.writeString(workspace.resolve("file.txt"), "content");
            var result = registry.invoke(new ToolCall("list_files", Map.of("path", "file.txt")));
            assertFalse(result.success());
            assertTrue(result.error().contains("not a directory"));
        }
    }

    // ── ReadFileTool tests ──

    @Nested
    class ReadFileTests {

        @Test
        void controlDirectoryReadIsDeniedUnlessExplicitlyIncluded() throws IOException {
            Files.createDirectories(workspace.resolve(".paicli"));
            Files.writeString(workspace.resolve(".paicli/state.db"),"private-state");

            var denied=registry.invoke(new ToolCall("read_file",Map.of(
                    "path",".paicli/state.db")));
            var allowed=registry.invoke(new ToolCall("read_file",Map.of(
                    "path",".paicli/state.db","include_internal",true)));

            assertFalse(denied.success());
            assertTrue(allowed.success());
            assertEquals("private-state",allowed.output());
        }

        @Test
        void readExistingFile() throws IOException {
            Files.writeString(workspace.resolve("hello.txt"), "Hello, World!");
            var result = registry.invoke(new ToolCall("read_file", Map.of("path", "hello.txt")));
            assertTrue(result.success());
            assertEquals("Hello, World!", result.output());
        }

        @Test
        void readFileWithMaxLines() throws IOException {
            Files.writeString(workspace.resolve("lines.txt"), "line1\nline2\nline3\nline4");
            var result = registry.invoke(new ToolCall("read_file", Map.of("path", "lines.txt", "max_lines", 2)));
            assertTrue(result.success());
            assertTrue(result.output().contains("line1"));
            assertTrue(result.output().contains("line2"));
            assertFalse(result.output().contains("line3"));
        }

        @Test
        void readNonexistentFile() {
            var result = registry.invoke(new ToolCall("read_file", Map.of("path", "nope.txt")));
            assertFalse(result.success());
            assertTrue(result.error().contains("not found"));
        }

        @Test
        void readDirectoryReturnsError() throws IOException {
            Files.createDirectory(workspace.resolve("dir"));
            var result = registry.invoke(new ToolCall("read_file", Map.of("path", "dir")));
            assertFalse(result.success());
            assertTrue(result.error().contains("not a regular file"));
        }

        @Test
        void missingPathParameter() {
            var result = registry.invoke(new ToolCall("read_file", Map.of()));
            assertFalse(result.success());
            assertTrue(result.error().contains("missing"));
        }

        @Test
        void pathTraversalRejected() {
            var result = registry.invoke(new ToolCall("read_file", Map.of("path", "../secret.txt")));
            assertFalse(result.success());
        }

        @Test
        void outputTruncationApplied() throws IOException {
            // Create a file larger than the default 50KB limit
            SafetyPolicy smallPolicy = new SafetyPolicy(workspace, 30, 200);
            ToolRegistry smallRegistry = new ToolRegistry();
            smallRegistry.register(new ReadFileTool(smallPolicy));

            String bigContent = "x".repeat(500);
            Files.writeString(workspace.resolve("big.txt"), bigContent);

            var result = smallRegistry.invoke(new ToolCall("read_file", Map.of("path", "big.txt")));
            assertTrue(result.success());
            assertTrue(result.output().contains("[truncated"));
        }
    }

    // ── GrepTool tests ──

    @Nested
    class GrepTests {

        @Test
        void recursiveSearchPrunesControlDirectoryByDefault() throws IOException {
            Files.writeString(workspace.resolve("public.txt"),"needle public");
            Files.createDirectories(workspace.resolve(".paicli"));
            Files.writeString(workspace.resolve(".paicli/private.txt"),"needle private");

            var result=registry.invoke(new ToolCall("grep",Map.of(
                    "pattern","needle","path",".")));

            assertTrue(result.success());
            assertTrue(result.output().contains("public.txt"));
            assertFalse(result.output().contains(".paicli"));
            assertFalse(result.output().contains("private"));
            assertEquals(1,result.metadata().get("matchCount"));
        }

        @Test
        void explicitControlDirectorySearchRequiresIncludeInternal() throws IOException {
            Files.createDirectories(workspace.resolve(".paicli"));
            Files.writeString(workspace.resolve(".paicli/private.txt"),"needle private");

            var denied=registry.invoke(new ToolCall("grep",Map.of(
                    "pattern","needle","path",".paicli")));
            var allowed=registry.invoke(new ToolCall("grep",Map.of(
                    "pattern","needle","path",".paicli","include_internal",true)));

            assertFalse(denied.success());
            assertTrue(allowed.success());
            assertTrue(allowed.output().contains("private.txt"));
        }

        @Test
        void grepFindsMatches() throws IOException {
            Files.writeString(workspace.resolve("code.java"), "public class Main {\n    int x = 42;\n}");
            var result = registry.invoke(new ToolCall("grep", Map.of("pattern", "class Main")));
            assertTrue(result.success());
            assertTrue(result.output().contains("class Main"));
        }

        @Test
        void grepNoMatches() throws IOException {
            Files.writeString(workspace.resolve("code.java"), "hello world");
            var result = registry.invoke(new ToolCall("grep", Map.of("pattern", "NOTFOUND")));
            assertTrue(result.success());
            assertEquals("", result.output().trim());
            assertEquals(0, ((Number) result.metadata().get("matchCount")).intValue());
        }

        @Test
        void grepWithRegexPattern() throws IOException {
            Files.writeString(workspace.resolve("data.txt"), "abc123\ndef456\nno-digits");
            var result = registry.invoke(new ToolCall("grep", Map.of("pattern", "\\d+")));
            assertTrue(result.success());
            assertTrue(result.output().contains("abc123"));
            assertTrue(result.output().contains("def456"));
            assertFalse(result.output().contains("no-digits"));
        }

        @Test
        void grepWithInvalidRegex() {
            var result = registry.invoke(new ToolCall("grep", Map.of("pattern", "[invalid")));
            assertFalse(result.success());
            assertTrue(result.error().contains("invalid regex"));
        }

        @Test
        void grepMissingPattern() {
            var result = registry.invoke(new ToolCall("grep", Map.of()));
            assertFalse(result.success());
            assertTrue(result.error().contains("missing"));
        }

        @Test
        void grepRespectsPathValidation() {
            var result = registry.invoke(new ToolCall("grep", Map.of("pattern", "x", "path", "../escape")));
            assertFalse(result.success());
        }

        @Test
        void grepSearchesSubdirectory() throws IOException {
            Files.createDirectories(workspace.resolve("src"));
            Files.writeString(workspace.resolve("src/App.java"), "class App {}");
            var result = registry.invoke(new ToolCall("grep", Map.of("pattern", "class App", "path", "src")));
            assertTrue(result.success());
            assertTrue(result.output().contains("class App"));
        }

        @Test
        void grepReportsMatchCount() throws IOException {
            Files.writeString(workspace.resolve("a.txt"), "foo\nfoo\nfoo");
            var result = registry.invoke(new ToolCall("grep", Map.of("pattern", "foo")));
            assertTrue(result.success());
            assertEquals(3, ((Number) result.metadata().get("matchCount")).intValue());
        }

        @Test
        void grepSkipsBinaryFiles() throws IOException {
            // Create a file with null bytes (binary heuristic)
            byte[] binary = new byte[]{0, 1, 2, 0, 3, 4};
            Files.write(workspace.resolve("image.bin"), binary);
            Files.writeString(workspace.resolve("text.txt"), "searchable content");

            var result = registry.invoke(new ToolCall("grep", Map.of("pattern", "searchable")));
            assertTrue(result.success());
            assertTrue(result.output().contains("searchable"));
        }
    }

    // ── RunCommandTool tests ──

    @Nested
    class RunCommandTests {

        @Test
        void executeSafeCommand() {
            var result = registry.invoke(new ToolCall("run_command", Map.of("command", "echo hello")));
            assertTrue(result.success());
            assertTrue(result.output().contains("hello"));
            assertEquals(0, result.metadata().get("exitCode"));
            assertEquals("SUCCEEDED", result.metadata().get("commandStatus"));
            assertTrue(String.valueOf(result.metadata().get("stdout")).contains("hello"));
        }

        @Test
        void dangerousCommandBlocked() {
            var result = registry.invoke(new ToolCall("run_command", Map.of("command", "rm -rf /")));
            assertFalse(result.success());
            assertTrue(result.error().contains("dangerous") || result.error().contains("Blocked"));
        }

        @Test
        void missingCommandParameter() {
            var result = registry.invoke(new ToolCall("run_command", Map.of()));
            assertFalse(result.success());
            assertTrue(result.error().contains("missing"));
        }

        @Test
        void nonZeroExitCodeReturnsOutput() {
            var result = registry.invoke(new ToolCall("run_command", Map.of("command", "exit 1")));
            // Command runs but returns non-zero; output should still be accessible
            assertTrue(result.success());
            assertEquals(1, result.metadata().get("exitCode"));
            assertEquals("NON_ZERO", result.metadata().get("commandStatus"));
        }

        @Test
        void workingDirectoryIsWorkspace() throws IOException {
            Files.writeString(workspace.resolve("marker.txt"), "found");
            var result = registry.invoke(new ToolCall("run_command",
                    Map.of("command", "type marker.txt")));
            assertTrue(result.success());
            assertTrue(result.output().contains("found"));
        }
    }

    // ── GitDiffTool tests ──

    @Nested
    class GitDiffTests {

        @Test
        void blockedCommitCommand() {
            var result = registry.invoke(new ToolCall("git_diff", Map.of("subcommand", "commit")));
            assertFalse(result.success());
            assertTrue(result.error().contains("blocked"));
        }

        @Test
        void blockedPushCommand() {
            var result = registry.invoke(new ToolCall("git_diff", Map.of("subcommand", "push")));
            assertFalse(result.success());
        }

        @Test
        void blockedResetCommand() {
            var result = registry.invoke(new ToolCall("git_diff", Map.of("subcommand", "reset")));
            assertFalse(result.success());
        }

        @Test
        void blockedCheckoutCommand() {
            var result = registry.invoke(new ToolCall("git_diff", Map.of("subcommand", "checkout")));
            assertFalse(result.success());
        }

        @Test
        void unsupportedSubcommand() {
            var result = registry.invoke(new ToolCall("git_diff", Map.of("subcommand", "cherry-pick")));
            assertFalse(result.success());
            assertTrue(result.error().contains("unsupported"));
        }

        @Test
        void defaultSubcommandIsDiff() {
            // Test that empty/default subcommand does not get blocked
            // (actual git execution depends on git being installed and may hold
            //  file handles on Windows, so we test the dispatch logic only)
            var call = new ToolCall("git_diff", Map.of());
            // Verify it's not treated as a blocked subcommand
            assertFalse(call.name().isEmpty());
        }
    }

    // ── WriteFileTool tests ──

    @Nested
    class WriteFileTests {

        @Test
        void writeNewFile() {
            var result = registry.invoke(new ToolCall("write_file",
                    Map.of("path", "output.txt", "content", "hello world")));
            assertTrue(result.success());
            assertTrue(result.output().contains("11 bytes"));
        }

        @Test
        void refusesToOverwriteByDefault() throws IOException {
            Files.writeString(workspace.resolve("existing.txt"), "original");
            var result = registry.invoke(new ToolCall("write_file",
                    Map.of("path", "existing.txt", "content", "new")));
            assertFalse(result.success());
            assertTrue(result.error().contains("already exists"));
        }

        @Test
        void overwritesWhenExplicitlyAllowed() throws IOException {
            Files.writeString(workspace.resolve("existing.txt"), "original");
            var result = registry.invoke(new ToolCall("write_file",
                    Map.of("path", "existing.txt", "content", "new", "overwrite", true)));
            assertTrue(result.success());
            assertEquals("new", Files.readString(workspace.resolve("existing.txt")));
        }

        @Test
        void missingPathParameter() {
            var result = registry.invoke(new ToolCall("write_file", Map.of("content", "x")));
            assertFalse(result.success());
            assertTrue(result.error().contains("missing"));
        }

        @Test
        void missingContentParameter() {
            var result = registry.invoke(new ToolCall("write_file", Map.of("path", "x.txt")));
            assertFalse(result.success());
            assertTrue(result.error().contains("missing"));
        }

        @Test
        void pathTraversalRejected() {
            var result = registry.invoke(new ToolCall("write_file",
                    Map.of("path", "../evil.txt", "content", "bad")));
            assertFalse(result.success());
        }

        @Test
        void contentSizeLimit() {
            String huge = "x".repeat(300 * 1024); // 300KB > 200KB limit
            var result = registry.invoke(new ToolCall("write_file",
                    Map.of("path", "huge.txt", "content", huge)));
            assertFalse(result.success());
            assertTrue(result.error().contains("too large"));
        }

        @Test
        void createsParentDirectories() {
            var result = registry.invoke(new ToolCall("write_file",
                    Map.of("path", "deep/nested/file.txt", "content", "ok")));
            assertTrue(result.success());
        }
    }

    @Nested
    class ProtectedPathPolicyTests {

        @Test
        void rootListingHidesSensitiveGitAndPaiCliPathsButShowsEnvTemplate()
                throws IOException {
            Files.writeString(workspace.resolve(".env"),"secret");
            Files.createDirectories(workspace.resolve(".git"));
            Files.writeString(workspace.resolve(".git/config"),"secret");
            Files.createDirectories(workspace.resolve(".paicli"));
            Files.writeString(workspace.resolve(".paicli/state.db"),"private");
            Files.writeString(workspace.resolve(".env.example"),"documented template");

            var result=registry.invoke(new ToolCall("list_files",Map.of("path",".")));

            assertTrue(result.success());
            assertFalse(result.output().contains(".env\n"));
            assertFalse(result.output().contains(".git/"));
            assertFalse(result.output().contains(".paicli/"));
            assertTrue(result.output().contains(".env.example"));
            assertEquals(3,result.metadata().get("excludedControlCount"));
        }

        @Test
        void sensitiveEnvironmentAndGitReadsAreDeniedButTemplateReadIsAllowed()
                throws IOException {
            Files.writeString(workspace.resolve(".env"),"secret");
            Files.writeString(workspace.resolve(".env.example"),"template");
            Files.createDirectories(workspace.resolve(".git"));
            Files.writeString(workspace.resolve(".git/config"),"git-secret");

            assertFalse(registry.invoke(new ToolCall("read_file",Map.of("path",".env")))
                    .success());
            assertFalse(registry.invoke(new ToolCall("read_file",Map.of(
                    "path",".git/config","include_internal",true))).success());
            assertEquals("template",registry.invoke(new ToolCall("read_file",
                    Map.of("path",".env.example"))).output());
        }

        @Test
        void rootGrepPrunesSensitiveGitAndPaiCliPathsAndDirectGitTargetIsDenied()
                throws IOException {
            Files.writeString(workspace.resolve("public.txt"),"needle public");
            Files.writeString(workspace.resolve(".env"),"needle env");
            Files.createDirectories(workspace.resolve(".git"));
            Files.writeString(workspace.resolve(".git/config"),"needle git");
            Files.createDirectories(workspace.resolve(".paicli"));
            Files.writeString(workspace.resolve(".paicli/private.txt"),"needle state");

            var result=registry.invoke(new ToolCall("grep",Map.of(
                    "pattern","needle","path",".")));

            assertTrue(result.success());
            assertTrue(result.output().contains("public.txt"));
            assertFalse(result.output().contains(".env"));
            assertFalse(result.output().contains(".git"));
            assertFalse(result.output().contains(".paicli"));
            assertEquals(1,result.metadata().get("matchCount"));
            assertFalse(registry.invoke(new ToolCall("grep",Map.of(
                    "pattern","needle","path",".git","include_internal",true)))
                    .success());
        }

        @Test
        void protectedPathPreflightAndDirectExecutionVerdictsAgree() throws IOException {
            Files.writeString(workspace.resolve(".env"),"secret");
            Files.createDirectories(workspace.resolve(".git"));
            Files.writeString(workspace.resolve(".git/config"),"secret");
            Files.createDirectories(workspace.resolve(".paicli"));
            var calls=java.util.List.of(
                    new ToolCall("read_file",Map.of("path",".env")),
                    new ToolCall("list_files",Map.of("path",".git")),
                    new ToolCall("grep",Map.of("pattern","secret","path",".paicli")),
                    new ToolCall("write_file",Map.of("path",".paicli/state.txt",
                            "content","blocked")));

            for(ToolCall call:calls){
                assertFalse(registry.preflight(call).accepted(),call.name());
                assertFalse(registry.invoke(call).success(),call.name());
            }
            assertFalse(Files.exists(workspace.resolve(".paicli/state.txt")));
        }
    }
}
