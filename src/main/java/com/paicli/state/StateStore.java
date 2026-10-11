package com.paicli.state;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import com.paicli.interactive.view.InteractiveSessionViewModelReader;

public class StateStore implements AutoCloseable {

    private final DatabaseManager databaseManager;
    private final Connection connection;

    public final TaskStore tasks;
    public final EventStore events;
    public final ToolCallStore toolCalls;
    public final ApprovalStore approvals;
    public final CheckpointStore checkpoints;
    public final ProjectMemory memory;
    public final FileSnapshotStore snapshots;
    public final CodingRunStore codingRuns;
    public final TaskPlanStore plans;
    public final VerificationStore verifications;
    public final RepairAttemptStore repairAttempts;
    public final SkillLoadStore skillLoads;
    public final SkillSelectionStore skillSelections;
    public final HookRunStore hookRuns;
    public final CodeIndexStore codeIndex;
    public final CodeSearchAuditStore codeSearchAudits;
    public final SubagentRunStore subagentRuns;
    public final StructuredVerdictStore structuredVerdicts;
    public final LeadDecisionStore leadDecisions;
    public final ManagedWorktreeStore managedWorktrees;
    public final WorkerRunStore workerRuns;
    public final WorkerTaskStore workerTasks;
    public final TaskClaimStore taskClaims;
    public final WorkerMailboxStore workerMailbox;
    public final WorkerCandidateStore workerCandidates;
    public final WorkerMergeProposalStore workerMergeProposals;
    public final WorkerIntegrationDecisionStore workerIntegrationDecisions;
    public final WorkerIntegrationRunStore workerIntegrationRuns;
    public final WorkerCleanupStore workerCleanups;
    public final McpServerManifestStore mcpServerManifests;
    public final McpSessionStore mcpSessions;
    public final McpToolCatalogStore mcpToolCatalogs;
    public final McpToolStore mcpTools;
    public final McpToolCallStore mcpToolCalls;
    public final McpTaskBindingStore mcpTaskBindings;
    public final CoreDemoRunStore coreDemoRuns;
    public final CoreDemoCheckpointStore coreDemoCheckpoints;
    public final InteractiveTaskFactStore interactiveTaskFacts;
    public final InteractiveProposalStore interactiveProposals;
    /** Durable R1 identity, Manifest, occurrence and ownership boundary. */
    public final R1aDurableStore r1aDurable;
    public final FencedMutationStore fencedMutations;
    public final PlanAuthorityStore planAuthorities;
    public final PlanRequirementStore planRequirements;
    public final PlanExecutionStore planExecutions;
    public final PlanCompletionStore planCompletions;
    public final UserResultStore userResults;
    public final SteeringStore steerings;
    public final FollowUpStore followUps;
    public final LegacyAliasStore legacyAliases;
    public final LegacyBackfillPlanner legacyBackfill;
    public final SchemaIntegrityChecker schemaIntegrity;
    /** Independent non-authority V25 Provider call telemetry writer. */
    public final ProviderCallTelemetryStore providerTelemetry;
    /** R3 Context Generation and transactional compaction authority. */
    public final ContextGenerationStore contexts;
    /** R3 durable typed Context source closure assembler. */
    public final ContextSourceAssembler contextSources;
    /** Bounded same-Task segment accounting; not a scheduler or Tool authority. */
    public final TaskContinuityStore taskContinuity;
    public final com.paicli.runtime.RecoveryPreflightValidator recoveryPreflight;
    public final R1AuthorityPeek authorityPeeks;
    public final InteractiveSessionViewModelReader viewModels;

    public StateStore() throws IOException, SQLException {
        this(Path.of("."));
    }

    public StateStore(Path workspace) throws IOException, SQLException {
        this(standardLocation(workspace));
    }

    private StateStore(StoreLocation location) throws IOException, SQLException {
        DatabaseManager manager = new DatabaseManager(location.stateDirectory());
        Path runtimeDirectory = location.runtimeDirectory();
        try {
            Connection connection = manager.getConnection();
            new MigrationRunner(connection).runMigrations();

            this.databaseManager = manager;
            this.connection = connection;
            this.tasks = new TaskStore(connection);
            this.mcpTaskBindings = new McpTaskBindingStore(connection);
            this.events = new EventStore(connection);
            this.toolCalls = new ToolCallStore(connection);
            this.approvals = new ApprovalStore(connection);
            this.checkpoints = new CheckpointStore(connection, runtimeDirectory.resolve("checkpoints"));
            this.memory = new ProjectMemory(connection, runtimeDirectory.resolve("memory"));
            this.snapshots = new FileSnapshotStore(connection);
            this.codingRuns = new CodingRunStore(connection);
            this.plans = new TaskPlanStore(connection);
            this.verifications = new VerificationStore(connection);
            this.repairAttempts = new RepairAttemptStore(connection);
            this.skillLoads = new SkillLoadStore(connection);
            this.skillSelections = new SkillSelectionStore(connection);
            this.hookRuns = new HookRunStore(connection);
            this.codeIndex = new CodeIndexStore(connection);
            this.codeSearchAudits = new CodeSearchAuditStore(connection);
            this.subagentRuns = new SubagentRunStore(connection);
            this.structuredVerdicts = new StructuredVerdictStore(connection);
            this.leadDecisions = new LeadDecisionStore(connection);
            this.managedWorktrees = new ManagedWorktreeStore(connection);
            this.workerRuns = new WorkerRunStore(connection);
            this.workerTasks = new WorkerTaskStore(connection);
            this.taskClaims = new TaskClaimStore(connection);
            this.workerMailbox = new WorkerMailboxStore(connection);
            this.workerCandidates = new WorkerCandidateStore(connection);
            this.workerMergeProposals = new WorkerMergeProposalStore(connection);
            this.workerIntegrationDecisions = new WorkerIntegrationDecisionStore(connection);
            this.workerIntegrationRuns = new WorkerIntegrationRunStore(connection);
            this.workerCleanups = new WorkerCleanupStore(connection);
            this.mcpServerManifests = new McpServerManifestStore(connection);
            this.mcpSessions = new McpSessionStore(connection);
            this.mcpTools = new McpToolStore(connection);
            this.mcpToolCatalogs = new McpToolCatalogStore(connection, mcpTools);
            this.mcpToolCalls = new McpToolCallStore(connection);
            this.coreDemoRuns = new CoreDemoRunStore(connection);
            this.coreDemoCheckpoints = new CoreDemoCheckpointStore(connection);
            this.interactiveTaskFacts = new InteractiveTaskFactStore(connection);
            this.interactiveProposals = new InteractiveProposalStore(connection);
            this.r1aDurable = new R1aDurableStore(connection);
            this.fencedMutations = new FencedMutationStore(connection);
            this.planAuthorities = new PlanAuthorityStore(connection);
            this.planRequirements = new PlanRequirementStore(connection);
            this.planExecutions = new PlanExecutionStore(connection);
            this.planCompletions = new PlanCompletionStore(connection);
            this.userResults = new UserResultStore(connection);
            this.steerings = new SteeringStore(connection);
            this.followUps = new FollowUpStore(connection);
            this.legacyAliases = new LegacyAliasStore(connection);
            this.legacyBackfill = new LegacyBackfillPlanner(connection);
            this.schemaIntegrity = new SchemaIntegrityChecker(connection);
            this.providerTelemetry = new ProviderCallTelemetryStore(location.stateDirectory());
            this.contexts = new ContextGenerationStore(connection);
            this.contextSources = new ContextSourceAssembler(connection);
            this.taskContinuity = new TaskContinuityStore(connection);
            Path viewWorkspace = runtimeDirectory.getParent() == null
                    ? runtimeDirectory : runtimeDirectory.getParent();
            this.recoveryPreflight=new com.paicli.runtime.RecoveryPreflightValidator(
                    this,new com.paicli.runtime.InstructionManifestRuntime(this,viewWorkspace),()->{
                com.paicli.config.Config current=
                        new com.paicli.config.ConfigLoader(viewWorkspace).load();
                return new com.paicli.runtime.RecoveryPreflightValidator.RuntimeInputs(
                        current,new com.paicli.runtime.RuntimeManifestHasher(viewWorkspace)
                        .compute(current),current.maxProjectInstructionChars());
            });
            this.authorityPeeks = new R1AuthorityPeek(connection, r1aDurable,
                    planAuthorities, planExecutions, planCompletions, steerings,
                    followUps, schemaIntegrity,recoveryPreflight);
            this.viewModels = new InteractiveSessionViewModelReader(
                    connection, viewWorkspace, r1aDurable, authorityPeeks);
        } catch (SQLException | RuntimeException e) {
            try {
                manager.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /** Executes related read surfaces in one SQLite query-only snapshot. */
    public <T> T readOnlySnapshot(SnapshotQuery<T> query) throws Exception {
        Objects.requireNonNull(query, "query");
        synchronized (connection) {
            if (!connection.getAutoCommit()) {
                throw new SQLException("read snapshot cannot nest");
            }
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
            }
            connection.setAutoCommit(false);
            try {
                T value = query.read();
                connection.commit();
                return value;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA query_only=OFF");
                }
            }
        }
    }

    @FunctionalInterface
    public interface SnapshotQuery<T> { T read() throws Exception; }

    private static StoreLocation workerLocation(Path stateDatabase, Path runtimeDirectory) {
        Path database = Objects.requireNonNull(stateDatabase, "stateDatabase")
                .toAbsolutePath().normalize();
        if (!"paicli.db".equals(database.getFileName().toString())) {
            throw new IllegalArgumentException("Worker state database must be named paicli.db");
        }
        Path runtime = Objects.requireNonNull(runtimeDirectory, "runtimeDirectory")
                .toAbsolutePath().normalize();
        return new StoreLocation(database.getParent(), runtime);
    }

    private static StoreLocation standardLocation(Path workspace) {
        Path root = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath().normalize();
        Path paicliDir = root.resolve(".paicli");
        return new StoreLocation(paicliDir.resolve("state"), paicliDir);
    }

    /** Opens an isolated Worker StateStore at runtime-derived state and trace locations. */
    public static StateStore openWorker(Path stateDatabase, Path traceDirectory)
            throws IOException, SQLException {
        return new StateStore(workerLocation(stateDatabase, traceDirectory));
    }

    /** Opens the dedicated Core Demo store outside the extracted Maven workspace. */
    public static StateStore openCoreDemo(Path stateDatabase, Path runRoot)
            throws IOException, SQLException {
        Path database = Objects.requireNonNull(stateDatabase, "stateDatabase")
                .toAbsolutePath().normalize();
        Path root = Objects.requireNonNull(runRoot, "runRoot")
                .toAbsolutePath().normalize();
        if (!"paicli.db".equals(database.getFileName().toString())
                || !database.getParent().equals(root.resolve("state"))) {
            throw new IllegalArgumentException(
                    "Core Demo state database must be <runRoot>/state/paicli.db");
        }
        return new StateStore(new StoreLocation(database.getParent(), root.resolve("state")));
    }

    @Override
    public void close() throws SQLException {
        databaseManager.close();
    }

    private record StoreLocation(Path stateDirectory, Path runtimeDirectory) { }
}
