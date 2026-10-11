package com.paicli.worktree;

import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.ManagedWorktreeStore.ManagedWorktreeRecord;
import com.paicli.worker.WorkerStateLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Runtime-owned lifecycle for native Git linked worktrees. */
public final class ManagedWorktreeService {
    public static final String SUPPORTED_BACKEND = ManagedWorktreeStore.BACKEND_GIT;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern COMMIT_SHA = Pattern.compile("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})");
    private static final Pattern FILTER_ATTRIBUTE = Pattern.compile(
            "(?m)(?:^|\\s)(?:filter\\s*=|filter\\b)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> OPERATION_MARKERS = Set.of(
            "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG",
            "rebase-merge", "rebase-apply");

    private final ManagedWorktreeStore store;
    private final Duration gitTimeout;
    private final int maxGitOutputBytes;
    private final GitProcessFactory gitProcessFactory;

    public ManagedWorktreeService(ManagedWorktreeStore store) {
        this(store, Duration.ofSeconds(30), 65_536);
    }

    public ManagedWorktreeService(ManagedWorktreeStore store, Duration gitTimeout,
                                  int maxGitOutputBytes) {
        this(store, gitTimeout, maxGitOutputBytes,
                hooks -> new HardenedGitProcess(hooks, gitTimeout, maxGitOutputBytes));
    }

    ManagedWorktreeService(ManagedWorktreeStore store, Duration gitTimeout,
                           int maxGitOutputBytes, GitProcessFactory gitProcessFactory) {
        this.store = Objects.requireNonNull(store, "store");
        this.gitTimeout = Objects.requireNonNull(gitTimeout, "gitTimeout");
        this.maxGitOutputBytes = maxGitOutputBytes;
        this.gitProcessFactory = Objects.requireNonNull(gitProcessFactory, "gitProcessFactory");
    }

    public CreateResult createLinkedWorktree(CreateRequest request)
            throws ManagedWorktreeException {
        Objects.requireNonNull(request, "request");
        if (!SUPPORTED_BACKEND.equals(request.backendType())) {
            throw error(ManagedWorktreeException.Code.UNSUPPORTED_BACKEND,
                    "DIRECTORY_EXPORT is reserved and unsupported in v0.9.0");
        }
        String safeId = safeId(request.runtimeId());
        Path repository = realDirectory(request.repositoryRoot(), "repository root");
        PreparedPaths paths = prepareManagedPaths(repository, request.managedRoot(), safeId);
        GitProcess git = gitProcessFactory.create(paths.hooksDirectory());
        RepositoryState before = inspectRepositoryState(repository, git);
        if (request.expectedBaseSha() != null
                && !before.headSha().equalsIgnoreCase(request.expectedBaseSha())) {
            throw error(ManagedWorktreeException.Code.BASE_DRIFT,
                    "Repository HEAD drifted from coordinator base SHA");
        }
        String branch = deriveBranch(safeId);
        requireSuccess(git.execute(repository, HardenedGitProcess.Operation.CHECK_BRANCH, branch),
                "Derived branch is invalid");
        checkCollisions(safeId, before, paths.workspace(), branch, git);

        WorkerStateLocation.Location location = new WorkerStateLocation(
                paths.managedRoot(), paths.workspace()).locate(safeId);
        boolean metadataCreated = false;
        boolean resourcesMayExist = false;
        try {
            store.create(safeId, SUPPORTED_BACKEND, before.repositoryIdentity(),
                    before.headSha(), branch, paths.workspace().toString(),
                    location.stateDatabase().toString(), location.traceDirectory().toString(),
                    before.dirty() ? "DIRTY" : "CLEAN");
            metadataCreated = true;

            HardenedGitProcess.Result add = git.execute(repository,
                    HardenedGitProcess.Operation.WORKTREE_ADD, branch,
                    paths.workspace().toString(), before.headSha());
            resourcesMayExist = true;
            requireSuccess(add, "Git linked worktree creation failed");
            verifyCreated(repository, paths.workspace(), branch, before, git);
            store.markActive(safeId, null);
            ManagedWorktreeRecord record = store.findById(safeId)
                    .orElseThrow(() -> error(ManagedWorktreeException.Code.PERSISTENCE_FAILED,
                            "Managed worktree metadata disappeared after activation"));
            return new CreateResult(record, before, branch, paths.workspace());
        } catch (ManagedWorktreeException e) {
            if (metadataCreated) recordCreationFailure(safeId, repository, paths.workspace(),
                    branch, before, git, resourcesMayExist, e);
            throw e;
        } catch (SQLException e) {
            ManagedWorktreeException wrapped = new ManagedWorktreeException(
                    ManagedWorktreeException.Code.PERSISTENCE_FAILED,
                    "Managed worktree metadata update failed", e);
            if (metadataCreated) recordCreationFailure(safeId, repository, paths.workspace(),
                    branch, before, git, resourcesMayExist, wrapped);
            throw wrapped;
        } catch (RuntimeException e) {
            ManagedWorktreeException wrapped = new ManagedWorktreeException(
                    ManagedWorktreeException.Code.GIT_ERROR,
                    "Managed worktree creation failed", e);
            if (metadataCreated) recordCreationFailure(safeId, repository, paths.workspace(),
                    branch, before, git, resourcesMayExist, wrapped);
            throw wrapped;
        }
    }

    public RepositoryState inspectRepositoryState(Path repositoryRoot)
            throws ManagedWorktreeException {
        Path repository = realDirectory(repositoryRoot, "repository root");
        try (TemporaryHooks temporaryHooks = TemporaryHooks.create()) {
            return inspectRepositoryState(repository,
                    gitProcessFactory.create(temporaryHooks.path()));
        }
    }

    public Inspection inspectManagedWorktree(String managedWorktreeId, Path repositoryRoot)
            throws ManagedWorktreeException {
        ManagedWorktreeRecord record = findRecord(managedWorktreeId);
        if (!SUPPORTED_BACKEND.equals(record.backendType())) {
            throw error(ManagedWorktreeException.Code.UNSUPPORTED_BACKEND,
                    "DIRECTORY_EXPORT is reserved and unsupported in v0.9.0");
        }
        Path repository = realDirectory(repositoryRoot, "repository root");
        Path managed = Path.of(record.canonicalWorkspacePath()).getParent().getParent();
        Path hooks = ensureHooksDirectory(managed);
        GitProcess git = gitProcessFactory.create(hooks);
        RepositoryState state = inspectRepositoryState(repository, git);
        if (!state.repositoryIdentity().equals(record.repositoryIdentity())) {
            return new Inspection(record, Finding.REPOSITORY_MISMATCH, null,
                    "repository identity does not match metadata");
        }
        List<WorktreePorcelainParser.Entry> entries = listWorktrees(repository, git);
        Optional<WorktreePorcelainParser.Entry> entry = findByPath(entries,
                Path.of(record.canonicalWorkspacePath()));
        if (entry.isEmpty()) return new Inspection(record, Finding.MISSING, null,
                "Git worktree metadata is missing");
        String mismatch = mismatch(record, entry.get());
        return new Inspection(record, mismatch == null ? Finding.MATCHED : Finding.MISMATCH,
                entry.get(), mismatch);
    }

    public List<Inspection> recoverManagedWorktrees(Path repositoryRoot)
            throws ManagedWorktreeException {
        Path repository = realDirectory(repositoryRoot, "repository root");
        try (TemporaryHooks temporaryHooks = TemporaryHooks.create()) {
            GitProcess git = gitProcessFactory.create(temporaryHooks.path());
            RepositoryState state = inspectRepositoryState(repository, git);
            List<WorktreePorcelainParser.Entry> entries = listWorktrees(repository, git);
            List<Inspection> findings = new ArrayList<>();
            Set<Path> claimed = new HashSet<>();
            try {
                for (ManagedWorktreeRecord record : store.findAll()) {
                    if (!record.repositoryIdentity().equals(state.repositoryIdentity())) continue;
                    Path path = Path.of(record.canonicalWorkspacePath()).toAbsolutePath().normalize();
                    claimed.add(path);
                    Optional<WorktreePorcelainParser.Entry> actual = findByPath(entries, path);
                    String detail = actual.map(value -> mismatch(record, value)).orElse("worktree missing");
                    Finding finding = actual.isEmpty() ? Finding.MISSING
                            : detail == null ? Finding.MATCHED : Finding.MISMATCH;
                    Inspection inspection = new Inspection(record, finding, actual.orElse(null), detail);
                    findings.add(inspection);
                    if ((finding == Finding.MISSING || finding == Finding.MISMATCH)
                            && canMarkLost(record.status())) {
                        store.markLost(record.managedWorktreeId(), detail);
                    }
                }
            } catch (SQLException e) {
                throw new ManagedWorktreeException(ManagedWorktreeException.Code.PERSISTENCE_FAILED,
                        "Could not read managed worktree metadata", e);
            }
            for (WorktreePorcelainParser.Entry entry : entries) {
                Path normalized = entry.path().toAbsolutePath().normalize();
                if (!claimed.contains(normalized) && !samePath(normalized, repository)) {
                    findings.add(new Inspection(null, Finding.UNMANAGED_GIT_WORKTREE, entry,
                            "Git worktree has no coordinator metadata"));
                }
            }
            findings.sort(Comparator.comparing(value -> value.entry() == null
                    ? value.record().canonicalWorkspacePath() : value.entry().path().toString()));
            return List.copyOf(findings);
        }
    }

    public void assertUsable(String managedWorktreeId, Path repositoryRoot)
            throws ManagedWorktreeException {
        Inspection inspection = inspectManagedWorktree(managedWorktreeId, repositoryRoot);
        if (inspection.finding() != Finding.MATCHED
                || !ManagedWorktreeStore.STATUS_ACTIVE.equals(inspection.record().status())
                || inspection.entry().locked() || inspection.entry().prunable()) {
            throw error(ManagedWorktreeException.Code.RECONCILIATION_MISMATCH,
                    "Managed worktree is not active and exactly reconciled");
        }
    }

    /** Exact reconciliation for a completed dirty Worker before runtime candidate freeze. */
    public void assertFreezeable(String managedWorktreeId, Path repositoryRoot)
            throws ManagedWorktreeException {
        Inspection inspection = inspectManagedWorktree(managedWorktreeId, repositoryRoot);
        if (inspection.finding() != Finding.MATCHED
                || !ManagedWorktreeStore.STATUS_DIRTY.equals(inspection.record().status())
                || inspection.entry().locked() || inspection.entry().prunable()) {
            throw error(ManagedWorktreeException.Code.RECONCILIATION_MISMATCH,
                    "Managed worktree is not dirty and exactly reconciled for candidate freeze");
        }
    }

    /** Reconciles the worktree and returns its current bounded Git dirty state. */
    public boolean isWorktreeDirty(String managedWorktreeId, Path repositoryRoot)
            throws ManagedWorktreeException {
        assertUsable(managedWorktreeId, repositoryRoot);
        ManagedWorktreeRecord record = findRecord(managedWorktreeId);
        Path workspace = realDirectory(Path.of(record.canonicalWorkspacePath()),
                "managed worktree");
        Path managed = workspace.getParent().getParent();
        GitProcess git = gitProcessFactory.create(ensureHooksDirectory(managed));
        HardenedGitProcess.Result status = requireSuccess(
                git.execute(workspace, HardenedGitProcess.Operation.STATUS),
                "Managed worktree status inspection failed");
        String output = status.stdoutExcerpt() == null ? "" : status.stdoutExcerpt();
        if (truncated(output)) {
            throw error(ManagedWorktreeException.Code.GIT_ERROR,
                    "Managed worktree status exceeded the inspection bound");
        }
        return !output.isEmpty();
    }

    private RepositoryState inspectRepositoryState(Path repository, GitProcess git)
            throws ManagedWorktreeException {
        Path top = resolveGitPath(repository, requireSuccess(
                git.execute(repository, HardenedGitProcess.Operation.TOP_LEVEL),
                "Not a Git working tree").stdoutExcerpt());
        if (!samePath(top, repository)) {
            throw error(ManagedWorktreeException.Code.INVALID_REPOSITORY,
                    "Repository root must be the Git top-level working tree");
        }
        Path commonDir = resolveGitPath(repository, requireSuccess(
                git.execute(repository, HardenedGitProcess.Operation.COMMON_DIR),
                "Git common directory is unavailable").stdoutExcerpt());
        Path gitDir = resolveGitPath(repository, requireSuccess(
                git.execute(repository, HardenedGitProcess.Operation.GIT_DIR),
                "Git directory is unavailable").stdoutExcerpt());
        String head = singleLine(requireSuccess(git.execute(repository,
                HardenedGitProcess.Operation.HEAD), "Repository HEAD is unavailable").stdoutExcerpt());
        if (!COMMIT_SHA.matcher(head).matches()) {
            throw error(ManagedWorktreeException.Code.INVALID_REPOSITORY,
                    "Repository HEAD is not a full commit SHA");
        }
        requireSuccess(git.execute(repository, HardenedGitProcess.Operation.VERIFY_COMMIT, head),
                "Repository HEAD is not a valid commit object");
        rejectInProgressOperation(gitDir);
        rejectUnsupportedRepository(repository, gitDir, commonDir, head, git);
        HardenedGitProcess.Result status = requireSuccess(git.execute(repository,
                HardenedGitProcess.Operation.STATUS), "Repository status inspection failed");
        String statusText = status.stdoutExcerpt() == null ? "" : status.stdoutExcerpt();
        if (truncated(statusText)) throw unsupported("Repository status exceeded the inspection bound");
        return new RepositoryState(repository, commonDir, repositoryIdentity(commonDir),
                head.toLowerCase(Locale.ROOT), !statusText.isEmpty(), sha256(statusText));
    }

    private void rejectInProgressOperation(Path gitDir) throws ManagedWorktreeException {
        for (String marker : OPERATION_MARKERS) {
            if (Files.exists(gitDir.resolve(marker), LinkOption.NOFOLLOW_LINKS)) {
                throw error(ManagedWorktreeException.Code.REPOSITORY_BUSY,
                        "Repository has an unfinished Git operation: " + marker);
            }
        }
    }

    private void rejectUnsupportedRepository(Path repository, Path gitDir, Path commonDir,
                                             String head, GitProcess git)
            throws ManagedWorktreeException {
        HardenedGitProcess.Result filters = git.execute(repository,
                HardenedGitProcess.Operation.LOCAL_FILTER_CONFIG);
        if (filters.status() == HardenedGitProcess.Status.SUCCEEDED
                && filters.stdoutExcerpt() != null && !filters.stdoutExcerpt().isBlank()) {
            throw unsupported("Repository has checkout smudge/process filter configuration");
        }
        requireNoUnexpectedConfigFailure(filters, "filter configuration");

        HardenedGitProcess.Result sparse = git.execute(repository,
                HardenedGitProcess.Operation.LOCAL_SPARSE_CONFIG);
        if (sparse.status() == HardenedGitProcess.Status.SUCCEEDED
                && sparse.stdoutExcerpt() != null && !sparse.stdoutExcerpt().isBlank()) {
            throw unsupported("Sparse checkout/worktree config is unsupported");
        }
        requireNoUnexpectedConfigFailure(sparse, "sparse checkout configuration");

        HardenedGitProcess.Result tree = requireSuccess(git.execute(repository,
                HardenedGitProcess.Operation.TREE_LIST, head), "Base tree inspection failed");
        String listing = tree.stdoutExcerpt() == null ? "" : tree.stdoutExcerpt();
        if (truncated(listing)) throw unsupported("Base tree exceeded the inspection bound");
        for (String entry : listing.split("\\u0000", -1)) {
            if (entry.isEmpty()) continue;
            int tab = entry.indexOf('\t');
            if (tab < 0) throw unsupported("Base tree listing is malformed");
            String metadata = entry.substring(0, tab);
            String path = entry.substring(tab + 1);
            if (metadata.startsWith("160000 ") || path.equals(".gitmodules")) {
                throw unsupported("Submodules and gitlinks are unsupported");
            }
            if (path.equals(".gitattributes") || path.endsWith("/.gitattributes")) {
                HardenedGitProcess.Result attributes = requireSuccess(git.execute(repository,
                        HardenedGitProcess.Operation.SHOW_FILE, head, path),
                        "Base attributes inspection failed");
                if (truncated(attributes.stdoutExcerpt())
                        || FILTER_ATTRIBUTE.matcher(nullToEmpty(attributes.stdoutExcerpt())).find()) {
                    throw unsupported("Checkout filter attributes are unsupported");
                }
            }
        }
        for (Path attributes : List.of(gitDir.resolve("info").resolve("attributes"),
                commonDir.resolve("info").resolve("attributes"))) {
            if (Files.exists(attributes, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    String value = Files.readString(attributes, StandardCharsets.UTF_8);
                    if (FILTER_ATTRIBUTE.matcher(value).find()) {
                        throw unsupported("Git info attributes configure a checkout filter");
                    }
                } catch (IOException e) {
                    throw new ManagedWorktreeException(
                            ManagedWorktreeException.Code.UNSUPPORTED_REPOSITORY,
                            "Git info attributes could not be safely inspected", e);
                }
            }
        }
    }

    private void requireNoUnexpectedConfigFailure(HardenedGitProcess.Result result, String label)
            throws ManagedWorktreeException {
        if (result.status() == HardenedGitProcess.Status.NON_ZERO
                && Integer.valueOf(1).equals(result.exitCode())) return;
        if (result.status() != HardenedGitProcess.Status.SUCCEEDED) {
            throw processError(result, "Could not inspect " + label);
        }
    }

    private PreparedPaths prepareManagedPaths(Path repository, Path requestedRoot, String safeId)
            throws ManagedWorktreeException {
        Path normalized = Objects.requireNonNull(requestedRoot, "managedRoot")
                .toAbsolutePath().normalize();
        validateNoSymlinkAncestors(normalized);
        if (WorkerWorkspacePathGuard.overlaps(repository, canonicalProspective(normalized))) {
            throw error(ManagedWorktreeException.Code.PATH_UNSAFE,
                    "Managed root must be outside the main workspace");
        }
        try {
            Files.createDirectories(normalized);
            Path managed = normalized.toRealPath();
            Path workspaces = Files.createDirectories(managed.resolve("workspaces")).toRealPath();
            Path hooks = Files.createDirectories(managed.resolve("runtime-empty-hooks")).toRealPath();
            Path workspace = workspaces.resolve(safeId).normalize();
            if (!WorkerWorkspacePathGuard.inside(workspace, workspaces)
                    || WorkerWorkspacePathGuard.overlaps(workspace, repository)) {
                throw error(ManagedWorktreeException.Code.PATH_UNSAFE,
                        "Derived worktree path is unsafe");
            }
            validateNoSymlinkAncestors(workspace);
            return new PreparedPaths(managed, workspace, hooks);
        } catch (IOException e) {
            throw new ManagedWorktreeException(ManagedWorktreeException.Code.PATH_UNSAFE,
                    "Managed root could not be prepared safely", e);
        }
    }

    private void checkCollisions(String id, RepositoryState repository, Path workspace,
                                 String branch, GitProcess git)
            throws ManagedWorktreeException {
        try {
            if (store.findById(id).isPresent()
                    || store.findByWorkspacePath(workspace.toString()).isPresent()
                    || store.findByRepositoryAndBranch(repository.repositoryIdentity(), branch).isPresent()) {
                throw error(ManagedWorktreeException.Code.COLLISION,
                        "Managed worktree metadata collision");
            }
            for (ManagedWorktreeRecord existing : store.findAll()) {
                Path existingPath = Path.of(existing.canonicalWorkspacePath()).toAbsolutePath().normalize();
                if (WorkerWorkspacePathGuard.overlaps(workspace, existingPath)) {
                    throw error(ManagedWorktreeException.Code.COLLISION,
                            "Managed worktree path would be nested");
                }
            }
        } catch (SQLException e) {
            throw new ManagedWorktreeException(ManagedWorktreeException.Code.PERSISTENCE_FAILED,
                    "Could not check managed worktree metadata", e);
        }
        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw error(ManagedWorktreeException.Code.COLLISION,
                    "Derived worktree path already exists");
        }
        HardenedGitProcess.Result ref = git.execute(repository.repositoryRoot(),
                HardenedGitProcess.Operation.REF_EXISTS, branch);
        if (ref.succeeded()) throw error(ManagedWorktreeException.Code.COLLISION,
                "Derived worker branch already exists");
        if (!(ref.status() == HardenedGitProcess.Status.NON_ZERO
                && Integer.valueOf(1).equals(ref.exitCode()))) {
            throw processError(ref, "Worker branch collision check failed");
        }
        for (WorktreePorcelainParser.Entry entry : listWorktrees(repository.repositoryRoot(), git)) {
            if (WorkerWorkspacePathGuard.overlaps(workspace, entry.path())
                    || ("refs/heads/" + branch).equals(entry.branch())) {
                throw error(ManagedWorktreeException.Code.COLLISION,
                        "Git worktree metadata collision");
            }
        }
    }

    private void verifyCreated(Path repository, Path workspace, String branch,
                               RepositoryState before, GitProcess git)
            throws ManagedWorktreeException {
        try {
            Path workspaceReal = workspace.toRealPath();
            Path parentReal = workspace.getParent().toRealPath();
            if (!WorkerWorkspacePathGuard.inside(workspaceReal, parentReal)
                    || Files.isSymbolicLink(workspace) || isWindowsReparsePoint(workspace)) {
                throw error(ManagedWorktreeException.Code.POST_CREATE_VERIFICATION_FAILED,
                        "Created worktree canonical path escaped its managed parent");
            }
        } catch (IOException e) {
            throw new ManagedWorktreeException(
                    ManagedWorktreeException.Code.POST_CREATE_VERIFICATION_FAILED,
                    "Created worktree canonical path could not be verified", e);
        }
        List<WorktreePorcelainParser.Entry> entries = listWorktrees(repository, git);
        WorktreePorcelainParser.Entry entry = findByPath(entries, workspace)
                .orElseThrow(() -> error(
                        ManagedWorktreeException.Code.POST_CREATE_VERIFICATION_FAILED,
                        "Created worktree is absent from Git porcelain metadata"));
        if (!before.headSha().equalsIgnoreCase(entry.head())
                || !("refs/heads/" + branch).equals(entry.branch())) {
            throw error(ManagedWorktreeException.Code.POST_CREATE_VERIFICATION_FAILED,
                    "Created worktree branch or HEAD does not match the reservation");
        }
        String workerHead = singleLine(requireSuccess(git.execute(workspace,
                HardenedGitProcess.Operation.HEAD), "Created worktree HEAD is unavailable").stdoutExcerpt());
        Path workerCommon = resolveGitPath(workspace, requireSuccess(git.execute(workspace,
                HardenedGitProcess.Operation.COMMON_DIR),
                "Created worktree common directory is unavailable").stdoutExcerpt());
        RepositoryState after = inspectRepositoryState(repository, git);
        if (!before.headSha().equalsIgnoreCase(workerHead)
                || !samePath(before.commonGitDirectory(), workerCommon)
                || !before.headSha().equals(after.headSha())
                || !before.statusHash().equals(after.statusHash())) {
            throw error(ManagedWorktreeException.Code.POST_CREATE_VERIFICATION_FAILED,
                    "Post-create Git isolation verification failed");
        }
    }

    private void recordCreationFailure(String id, Path repository, Path workspace, String branch,
                                       RepositoryState before, GitProcess git,
                                       boolean resourcesMayExist, ManagedWorktreeException failure) {
        String compensation = resourcesMayExist
                ? compensate(repository, workspace, branch, before, git)
                : "no Git resource was handed to the runtime";
        try {
            store.markAbandoned(id, failure.code() + ": " + failure.getMessage()
                    + "; compensation=" + compensation);
        } catch (SQLException persistenceError) {
            failure.addSuppressed(persistenceError);
        }
    }

    private String compensate(Path repository, Path workspace, String branch,
                              RepositoryState before, GitProcess git) {
        try {
            List<WorktreePorcelainParser.Entry> entries = listWorktrees(repository, git);
            Optional<WorktreePorcelainParser.Entry> actual = findByPath(entries, workspace);
            boolean ownedWorktreeObserved = false;
            if (actual.isPresent()) {
                WorktreePorcelainParser.Entry entry = actual.get();
                if (!before.headSha().equalsIgnoreCase(entry.head())
                        || !("refs/heads/" + branch).equals(entry.branch())
                        || entry.locked()) return "residue retained: identity not safe";
                ownedWorktreeObserved = true;
                HardenedGitProcess.Result dirty = git.execute(workspace,
                        HardenedGitProcess.Operation.STATUS);
                if (!dirty.succeeded() || !nullToEmpty(dirty.stdoutExcerpt()).isEmpty()) {
                    return "residue retained: worktree is dirty or unreadable";
                }
                requireSuccess(git.execute(repository, HardenedGitProcess.Operation.WORKTREE_REMOVE,
                        workspace.toString()), "Safe create compensation could not remove worktree");
            }
            HardenedGitProcess.Result ref = git.execute(repository,
                    HardenedGitProcess.Operation.REF_SHA, branch);
            if (ownedWorktreeObserved && ref.succeeded()
                    && before.headSha().equalsIgnoreCase(singleLine(ref.stdoutExcerpt()))
                    && listWorktrees(repository, git).stream()
                    .noneMatch(entry -> ("refs/heads/" + branch).equals(entry.branch()))) {
                requireSuccess(git.execute(repository, HardenedGitProcess.Operation.BRANCH_DELETE, branch),
                        "Safe create compensation could not remove branch");
            } else if (ref.succeeded()) {
                return "residue retained: branch moved or remains in use";
            }
            return "safe create compensation completed";
        } catch (Exception e) {
            return "residue retained: compensation could not be proven safe";
        }
    }

    private List<WorktreePorcelainParser.Entry> listWorktrees(Path repository,
                                                               GitProcess git)
            throws ManagedWorktreeException {
        String output = nullToEmpty(requireSuccess(git.execute(repository,
                HardenedGitProcess.Operation.WORKTREE_LIST),
                "Git worktree metadata inspection failed").stdoutExcerpt());
        if (truncated(output)) throw error(ManagedWorktreeException.Code.RECONCILIATION_MISMATCH,
                "Git worktree metadata exceeded the inspection bound");
        return new WorktreePorcelainParser().parse(output);
    }

    private String mismatch(ManagedWorktreeRecord record, WorktreePorcelainParser.Entry entry) {
        if (!record.baseCommitSha().equalsIgnoreCase(entry.head())) return "base SHA mismatch";
        if (!("refs/heads/" + record.workerBranch()).equals(entry.branch())) return "branch mismatch";
        if (entry.prunable()) return "worktree is prunable";
        return null;
    }

    private boolean canMarkLost(String status) {
        return Set.of(ManagedWorktreeStore.STATUS_ALLOCATING,
                ManagedWorktreeStore.STATUS_ACTIVE, "DIRTY", "CANDIDATE_FROZEN",
                ManagedWorktreeStore.STATUS_ABANDONED).contains(status);
    }

    private Optional<WorktreePorcelainParser.Entry> findByPath(
            List<WorktreePorcelainParser.Entry> entries, Path path) {
        return entries.stream().filter(entry -> samePath(entry.path(), path)).findFirst();
    }

    private ManagedWorktreeRecord findRecord(String id) throws ManagedWorktreeException {
        try {
            return store.findById(safeId(id)).orElseThrow(() -> error(
                    ManagedWorktreeException.Code.INVALID_REQUEST,
                    "Managed worktree metadata not found"));
        } catch (SQLException e) {
            throw new ManagedWorktreeException(ManagedWorktreeException.Code.PERSISTENCE_FAILED,
                    "Could not load managed worktree metadata", e);
        }
    }

    private HardenedGitProcess.Result requireSuccess(HardenedGitProcess.Result result, String message)
            throws ManagedWorktreeException {
        if (result.succeeded()) return result;
        throw processError(result, message);
    }

    private ManagedWorktreeException processError(HardenedGitProcess.Result result, String message) {
        ManagedWorktreeException.Code code = switch (result.status()) {
            case TIMED_OUT -> ManagedWorktreeException.Code.GIT_TIMEOUT;
            case NON_ZERO -> ManagedWorktreeException.Code.GIT_NON_ZERO;
            case ERROR -> ManagedWorktreeException.Code.GIT_NOT_AVAILABLE;
            case SUCCEEDED -> ManagedWorktreeException.Code.GIT_ERROR;
        };
        String detail = result.stderrExcerpt() == null ? "" : ": " + result.stderrExcerpt();
        return error(code, message + detail);
    }

    private Path resolveGitPath(Path workingDirectory, String output)
            throws ManagedWorktreeException {
        String value = singleLine(output);
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) path = workingDirectory.resolve(path);
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new ManagedWorktreeException(ManagedWorktreeException.Code.INVALID_REPOSITORY,
                    "Git returned an invalid repository path", e);
        }
    }

    private String singleLine(String value) throws ManagedWorktreeException {
        String result = nullToEmpty(value).trim();
        if (result.isEmpty() || result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0) {
            throw error(ManagedWorktreeException.Code.GIT_ERROR,
                    "Git returned an invalid single-value response");
        }
        return result;
    }

    private Path ensureHooksDirectory(Path managedRoot) throws ManagedWorktreeException {
        try {
            return Files.createDirectories(managedRoot.resolve("runtime-empty-hooks")).toRealPath();
        } catch (IOException e) {
            throw new ManagedWorktreeException(ManagedWorktreeException.Code.PATH_UNSAFE,
                    "Runtime hooks directory could not be prepared", e);
        }
    }

    private Path realDirectory(Path path, String label) throws ManagedWorktreeException {
        try {
            Path normalized = Objects.requireNonNull(path, label).toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(normalized)) {
                throw error(ManagedWorktreeException.Code.INVALID_REPOSITORY,
                        label + " must be a normal directory");
            }
            return normalized.toRealPath();
        } catch (IOException e) {
            throw new ManagedWorktreeException(ManagedWorktreeException.Code.INVALID_REPOSITORY,
                    label + " cannot be resolved", e);
        }
    }

    private void validateNoSymlinkAncestors(Path path) throws ManagedWorktreeException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || isWindowsReparsePoint(current))) {
                throw error(ManagedWorktreeException.Code.PATH_UNSAFE,
                        "Managed worktree path contains a symlink ancestor");
            }
            current = current.getParent();
        }
    }

    private boolean isWindowsReparsePoint(Path path) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return false;
        }
        try {
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (IOException | UnsupportedOperationException e) {
            return true;
        }
    }

    private Path canonicalProspective(Path path) throws ManagedWorktreeException {
        Path normalized = path.toAbsolutePath().normalize();
        Path ancestor = normalized;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) throw error(ManagedWorktreeException.Code.PATH_UNSAFE,
                "Managed root has no existing ancestor");
        try {
            return ancestor.toRealPath().resolve(ancestor.relativize(normalized)).normalize();
        } catch (IOException e) {
            throw new ManagedWorktreeException(ManagedWorktreeException.Code.PATH_UNSAFE,
                    "Managed root cannot be canonicalized", e);
        }
    }

    private String safeId(String value) throws ManagedWorktreeException {
        if (value == null || !SAFE_ID.matcher(value).matches()
                || value.equals(".") || value.equals("..")) {
            throw error(ManagedWorktreeException.Code.INVALID_REQUEST,
                    "runtimeId must be a bounded safe identifier");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String deriveBranch(String safeId) { return "paicli/worker/" + safeId; }

    private String repositoryIdentity(Path commonDir) { return sha256(commonDir.toString()); }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean samePath(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException e) {
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }
    }

    private boolean truncated(String value) {
        return value != null && value.contains("...[stream truncated;");
    }

    private String nullToEmpty(String value) { return value == null ? "" : value; }

    private ManagedWorktreeException unsupported(String message) {
        return error(ManagedWorktreeException.Code.UNSUPPORTED_REPOSITORY, message);
    }

    private ManagedWorktreeException error(ManagedWorktreeException.Code code, String message) {
        return new ManagedWorktreeException(code, message);
    }

    public record CreateRequest(Path repositoryRoot, Path managedRoot, String runtimeId,
                                String backendType, String expectedBaseSha) {
        public CreateRequest {
            if (expectedBaseSha != null && !COMMIT_SHA.matcher(expectedBaseSha).matches()) {
                throw new IllegalArgumentException("expectedBaseSha must be a full commit SHA");
            }
        }

        public CreateRequest(Path repositoryRoot, Path managedRoot, String runtimeId,
                             String backendType) {
            this(repositoryRoot, managedRoot, runtimeId, backendType, null);
        }

        public static CreateRequest linked(Path repositoryRoot, Path managedRoot, String runtimeId) {
            return new CreateRequest(repositoryRoot, managedRoot, runtimeId, SUPPORTED_BACKEND, null);
        }

        /** Trusted coordinator entry; current repository HEAD is still verified by the service. */
        public static CreateRequest linkedAtBase(Path repositoryRoot, Path managedRoot,
                                                  String runtimeId, String expectedBaseSha) {
            return new CreateRequest(repositoryRoot, managedRoot, runtimeId,
                    SUPPORTED_BACKEND, expectedBaseSha);
        }
    }

    public record CreateResult(ManagedWorktreeRecord record, RepositoryState repositoryState,
                               String branch, Path workspace) { }

    public record RepositoryState(Path repositoryRoot, Path commonGitDirectory,
                                  String repositoryIdentity, String headSha, boolean dirty,
                                  String statusHash) { }

    public record Inspection(ManagedWorktreeRecord record, Finding finding,
                             WorktreePorcelainParser.Entry entry, String detail) { }

    public enum Finding { MATCHED, MISSING, MISMATCH, REPOSITORY_MISMATCH,
        UNMANAGED_GIT_WORKTREE }

    private record PreparedPaths(Path managedRoot, Path workspace, Path hooksDirectory) { }

    private record TemporaryHooks(Path path) implements AutoCloseable {
        private static TemporaryHooks create() throws ManagedWorktreeException {
            try {
                return new TemporaryHooks(Files.createTempDirectory("paicli-git-hooks-"));
            } catch (IOException e) {
                throw new ManagedWorktreeException(ManagedWorktreeException.Code.PATH_UNSAFE,
                        "Could not prepare runtime Git hooks directory", e);
            }
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(path.resolve(".paicli-empty-gitconfig"));
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // The directory is empty and bounded; failure does not broaden cleanup.
            }
        }
    }

    @FunctionalInterface
    interface GitProcessFactory { GitProcess create(Path hooksDirectory); }
}

