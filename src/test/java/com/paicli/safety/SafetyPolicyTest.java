package com.paicli.safety;

import com.paicli.model.ToolCall;
import com.paicli.safety.SafetyViolationException.ViolationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SafetyPolicyTest {

    @TempDir
    Path workspace;

    SafetyPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SafetyPolicy(workspace);
    }

    // ── Path validation ──

    @Test
    void acceptsPathInsideWorkspace() throws SafetyViolationException {
        assertDoesNotThrow(() -> policy.validatePath(Path.of("src", "Main.java")));
    }

    @Test
    void acceptsCurrentDir() throws SafetyViolationException {
        assertDoesNotThrow(() -> policy.validatePath(Path.of(".")));
    }

    @Test
    void rejectsPathTraversalWithDotDot() {
        SafetyViolationException ex = assertThrows(SafetyViolationException.class,
                () -> policy.validatePath(Path.of("..", "etc", "passwd")));
        assertEquals(ViolationType.PATH_TRAVERSAL, ex.violationType());
    }

    @Test
    void rejectsAbsoluteOutsideWorkspace() {
        SafetyViolationException ex = assertThrows(SafetyViolationException.class,
                () -> policy.validatePath(Path.of("/etc/passwd")));
        assertEquals(ViolationType.PATH_OUTSIDE_WORKSPACE, ex.violationType());
    }

    @Test
    void rejectsDeeplyNestedTraversal() {
        SafetyViolationException ex = assertThrows(SafetyViolationException.class,
                () -> policy.validatePath(Path.of("a", "b", "..", "..", "..", "etc")));
        assertEquals(ViolationType.PATH_TRAVERSAL, ex.violationType());
    }

    @Test
    void normalizesRedundantSeparators() throws SafetyViolationException {
        // Path without ".." — normalization should not cause issues
        assertDoesNotThrow(() -> policy.validatePath(Path.of("src", "main", "java")));
    }

    @Test
    void handlesWindowsBackslashPaths() throws SafetyViolationException {
        // Path.of on Windows handles backslashes naturally
        assertDoesNotThrow(() -> policy.validatePath(Path.of("src\\main\\java")));
    }

    @Test
    void rejectsNullPath() {
        assertThrows(SafetyViolationException.class, () -> policy.validatePath(null));
    }

    // ── Command validation ──

    @Test
    void rejectsRmRf() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("rm -rf /"));
    }

    @Test
    void rejectsRmFr() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("rm -fr /tmp"));
    }

    @Test
    void rejectsRmRecursiveForce() {
        assertThrows(SafetyViolationException.class,
                () -> policy.validateCommand("rm --recursive --force /"));
    }

    @Test
    void rejectsDelSlashS() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("del /s /q C:\\"));
    }

    @Test
    void rejectsDelUppercaseS() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("del /S file.txt"));
    }

    @Test
    void rejectsFormatCommand() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("format C:"));
    }

    @Test
    void rejectsShutdownCommand() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("shutdown -s -t 0"));
    }

    @Test
    void rejectsMkfsCommand() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("mkfs.ext4 /dev/sda1"));
    }

    @Test
    void rejectsDdIfCommand() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("dd if=/dev/zero of=/dev/sda"));
    }

    @Test
    void rejectsDangerousCommandCaseInsensitive() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand("RM -RF /"));
    }

    @Test
    void acceptsSafeCommand() throws SafetyViolationException {
        assertDoesNotThrow(() -> policy.validateCommand("ls -la"));
    }

    @Test
    void acceptsFormatInGitCommand() throws SafetyViolationException {
        assertDoesNotThrow(() -> policy.validateCommand("git log --format=oneline"));
    }

    @Test
    void acceptsShutdownInText() throws SafetyViolationException {
        assertDoesNotThrow(() -> policy.validateCommand("echo system shutdown now"));
    }

    @Test
    void acceptsShutdownHelp() throws SafetyViolationException {
        assertDoesNotThrow(() -> policy.validateCommand("shutdown /?"));
    }

    @Test
    void acceptsGitCommand() throws SafetyViolationException {
        assertDoesNotThrow(() -> policy.validateCommand("git status"));
    }

    @Test
    void acceptsCatCommand() throws SafetyViolationException {
        assertDoesNotThrow(() -> policy.validateCommand("cat file.txt"));
    }

    @Test
    void rejectsNullCommand() {
        assertThrows(SafetyViolationException.class, () -> policy.validateCommand(null));
    }

    // ── Output truncation ──

    @Test
    void doesNotTruncateSmallOutput() {
        String output = "hello world";
        assertEquals(output, policy.truncateOutput(output));
    }

    @Test
    void truncatesLargeOutput() {
        // Default max is 50KB
        SafetyPolicy smallPolicy = new SafetyPolicy(workspace, 30, 100);
        String large = "x".repeat(200);
        String result = smallPolicy.truncateOutput(large);
        assertTrue(result.length() < large.length());
        assertTrue(result.contains("[truncated"));
    }

    @Test
    void truncationAppendsMarker() {
        SafetyPolicy smallPolicy = new SafetyPolicy(workspace, 30, 100);
        String large = "x".repeat(200);
        String result = smallPolicy.truncateOutput(large);
        assertTrue(result.endsWith("[truncated at 0KB]") || result.contains("[truncated at "));
    }

    @Test
    void handlesEmptyOutput() {
        assertEquals("", policy.truncateOutput(""));
    }

    @Test
    void handlesNullOutput() {
        assertEquals("", policy.truncateOutput(null));
    }

    @Test
    void doesNotTruncateExactBoundarySize() {
        SafetyPolicy smallPolicy = new SafetyPolicy(workspace, 30, 100);
        String exact = "x".repeat(100);
        assertEquals(exact, smallPolicy.truncateOutput(exact));
    }

    // ── ToolCall validation ──

    @Test
    void validatesPathFromToolCall() throws SafetyViolationException {
        var call = new ToolCall("list_files", Map.of("path", "src"));
        assertDoesNotThrow(() -> policy.validateToolCall(call));
    }

    @Test
    void rejectsEscapePathFromToolCall() {
        var call = new ToolCall("read_file", Map.of("path", "../../../etc/passwd"));
        assertThrows(SafetyViolationException.class, () -> policy.validateToolCall(call));
    }

    @Test
    void validatesCommandFromToolCall() {
        var call = new ToolCall("run_command", Map.of("command", "rm -rf /"));
        assertThrows(SafetyViolationException.class, () -> policy.validateToolCall(call));
    }

    @Test
    void passesThroughUnknownTool() throws SafetyViolationException {
        var call = new ToolCall("grep", Map.of("pattern", "foo"));
        assertDoesNotThrow(() -> policy.validateToolCall(call));
    }

    @Test
    void handlesNullToolCall() {
        assertDoesNotThrow(() -> policy.validateToolCall(null));
    }

    @Test
    void rejectsPathToolMissingPathArg() {
        var call = new ToolCall("list_files", Map.of());
        assertThrows(SafetyViolationException.class, () -> policy.validateToolCall(call));
    }

    @Test
    void rejectsPathToolNonStringPathArg() {
        var call = new ToolCall("read_file", Map.of("path", 42));
        assertThrows(SafetyViolationException.class, () -> policy.validateToolCall(call));
    }

    @Test
    void rejectsRunCommandMissingCommandArg() {
        var call = new ToolCall("run_command", Map.of());
        assertThrows(SafetyViolationException.class, () -> policy.validateToolCall(call));
    }

    // ── Configuration tests ──

    @Test
    void usesDefaultTimeout() {
        assertEquals(30, policy.getTimeoutSeconds());
    }

    @Test
    void usesDefaultMaxOutput() {
        assertEquals(50 * 1024, policy.getMaxOutputBytes());
    }

    @Test
    void acceptsCustomConfig() {
        SafetyPolicy custom = new SafetyPolicy(workspace, 60, 1024 * 1024);
        assertEquals(60, custom.getTimeoutSeconds());
        assertEquals(1024 * 1024, custom.getMaxOutputBytes());
    }

    @Test
    void workspaceRootIsNormalized() {
        SafetyPolicy custom = new SafetyPolicy(workspace.resolve("a").resolve(".."));
        assertEquals(workspace.toAbsolutePath().normalize(), custom.getWorkspaceRoot());
    }
}
