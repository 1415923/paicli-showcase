package com.paicli.demo.approval;

import com.paicli.demo.fixture.CoreDemoFixtureManifest;
import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;
import com.paicli.demo.workspace.CoreDemoWorkspace;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Fixed headless policy. It grants only scenario-manifest operations and never
 * delegates to interactive approval or changes ordinary ApprovalPolicy behavior.
 */
public final class CoreDemoApprovalProfile {
    private static final Set<String> READ_TOOLS = Set.of("read_file", "list_files", "grep");
    private static final Set<String> ALWAYS_DENIED = Set.of(
            "run_command", "git_diff", "mailbox_post", "mcp", "worker",
            "subagent", "shell", "git", "delete_file", "move_file", "rename_file");
    private final Path workspace;
    private final String workspaceIdentityHash;
    private final Set<String> mutablePaths;

    private CoreDemoApprovalProfile(Path workspace, String identity, Set<String> mutablePaths) {
        this.workspace = workspace;
        this.workspaceIdentityHash = identity;
        this.mutablePaths = Set.copyOf(mutablePaths);
    }

    public static CoreDemoApprovalProfile derive(
            CoreDemoWorkspace owned, CoreDemoFixtureManifest manifest) throws IOException {
        Objects.requireNonNull(owned, "owned");
        Objects.requireNonNull(manifest, "manifest");
        if (!owned.expectedSeedHash().equals(manifest.expectedBaselineTreeHash())) {
            throw new IllegalArgumentException("Workspace/manifest seed mismatch");
        }
        Path workspace = owned.workspace().toRealPath(LinkOption.NOFOLLOW_LINKS);
        return new CoreDemoApprovalProfile(
                workspace, owned.workspaceIdentityHash(),
                new HashSet<>(manifest.allowedMutablePaths()));
    }

    public Decision evaluate(String toolName, String relativePath, Mutation mutation) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(mutation, "mutation");
        if (ALWAYS_DENIED.contains(toolName) || toolName.startsWith("mcp.")) {
            return Decision.deny("CAPABILITY_DENIED");
        }
        if ("code_intelligence".equals(toolName)) {
            return mutation == Mutation.QUERY
                    ? Decision.allow("FIXED_CODE_INTELLIGENCE")
                    : Decision.deny("CAPABILITY_DENIED");
        }
        if ("configured_verification".equals(toolName)) {
            return mutation == Mutation.VERIFY
                    ? Decision.allow("FIXED_VERIFICATION")
                    : Decision.deny("CAPABILITY_DENIED");
        }
        Path relative;
        try {
            CoreDemoFixtureManifestLoader.validateRelativePath(relativePath);
            relative = Path.of(relativePath.replace('/', java.io.File.separatorChar)).normalize();
        } catch (Exception e) {
            return Decision.deny("UNSAFE_PATH");
        }
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) return Decision.deny("WORKSPACE_ESCAPE");
        Path actual;
        try {
            actual = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return Decision.deny("FILE_IDENTITY_DRIFT");
        }
        if (!actual.startsWith(workspace)) return Decision.deny("WORKSPACE_ESCAPE");
        if (READ_TOOLS.contains(toolName) && mutation == Mutation.READ) {
            return Decision.allow("OWNED_WORKSPACE_READ");
        }
        if (!"write_file".equals(toolName) || mutation != Mutation.WRITE) {
            return Decision.deny("CAPABILITY_DENIED");
        }
        if (!mutablePaths.contains(relativePath)) return Decision.deny("IMMUTABLE_PATH");
        if (!Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(actual)) {
            return Decision.deny("FILE_IDENTITY_DRIFT");
        }
        return new Decision(true, true, "EXACT_MUTABLE_SOURCE");
    }

    public String workspaceIdentityHash() { return workspaceIdentityHash; }
    public Set<String> mutablePaths() { return mutablePaths; }
    public boolean interactiveApprovalAvailable() { return false; }

    public enum Mutation { READ, WRITE, QUERY, VERIFY, CREATE, DELETE, RENAME, EXECUTE }

    public record Decision(boolean allowed, boolean snapshotRequired, String category) {
        static Decision allow(String category) { return new Decision(true, false, category); }
        static Decision deny(String category) { return new Decision(false, false, category); }
    }
}
