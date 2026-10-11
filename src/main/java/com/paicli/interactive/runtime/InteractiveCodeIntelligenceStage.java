package com.paicli.interactive.runtime;

import com.paicli.codeindex.CodeIndexer;
import com.paicli.codeindex.JavaSourceDiscovery;
import com.paicli.codeindex.JavaSourceParser;
import com.paicli.codeintelligence.CodeContextBuilder;
import com.paicli.codeintelligence.CodeIntelligenceResult;
import com.paicli.codeintelligence.CodeIntelligenceService;
import com.paicli.codeintelligence.DirectCallSiteSearch;
import com.paicli.codeintelligence.SymbolLookupService;
import com.paicli.state.StateStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded production Java code-intelligence stage for interactive task context. */
public final class InteractiveCodeIntelligenceStage {
    private static final Pattern CLASS_TOKEN = Pattern.compile("\\b[A-Z][A-Za-z0-9_$]{1,127}\\b");
    private static final int MAX_QUERIES = 4;
    private final JavaSourceDiscovery sourceDiscovery;

    public InteractiveCodeIntelligenceStage() {
        this(new JavaSourceDiscovery());
    }

    InteractiveCodeIntelligenceStage(JavaSourceDiscovery sourceDiscovery) {
        this.sourceDiscovery = java.util.Objects.requireNonNull(sourceDiscovery);
    }

    public CodeFacts inspect(Path workspace, String taskText,
            List<String> fileReferences, StateStore stateStore) throws Exception {
        return inspect(workspace, taskText, fileReferences, stateStore,
                JavaSourceDiscovery.CancellationProbe.NOOP);
    }

    public CodeFacts inspect(Path workspace, String taskText,
            List<String> fileReferences, StateStore stateStore,
            JavaSourceDiscovery.CancellationProbe cancellationProbe) throws Exception {
        com.paicli.diagnostic.StageTrace.event(
                "CODE_CANDIDATE_EXTRACTION_START", false,
                "file_reference_count", fileReferences.size());
        Set<String> candidates = candidates(taskText, fileReferences);
        com.paicli.diagnostic.StageTrace.event(
                "CODE_CANDIDATE_EXTRACTION_END", true,
                "candidate_count", candidates.size(),
                "file_reference_count", fileReferences.size(),
                "candidate_present", !candidates.isEmpty());

        if (candidates.isEmpty()) {
            com.paicli.diagnostic.StageTrace.event(
                    "CODE_SCAN_SKIPPED", true,
                    "reason", "NO_CODE_CANDIDATES_OR_FILE_REFERENCES",
                    "candidate_count", 0,
                    "file_reference_count", fileReferences.size());
            return new CodeFacts(0, 0, 0, List.of(), List.of(), true);
        }

        AtomicReference<JavaSourceDiscovery.ProgressSnapshot> lastProgress =
                new AtomicReference<>();
        com.paicli.diagnostic.StageTrace.event(
                "CODE_SCAN_START", true,
                "workspace", com.paicli.diagnostic.StageTrace.canonicalPath(workspace),
                "candidate_count", candidates.size());
        JavaSourceDiscovery.DiscoveryResult discovery;
        try {
            discovery = sourceDiscovery.discover(workspace, snapshot -> {
                lastProgress.set(snapshot);
                com.paicli.diagnostic.StageTrace.event(
                        "CODE_SCAN_PROGRESS", true,
                        "visited_directories", snapshot.visitedDirectories(),
                        "visited_files", snapshot.visitedFiles(),
                        "matched_java_files", snapshot.matchedJavaFiles(),
                        "approximate_bytes", snapshot.approximateBytes(),
                        "elapsed_ms", snapshot.elapsedMillis(),
                        "current_depth", snapshot.currentDepth(),
                        "max_depth", snapshot.maxDepth(),
                        "crossed_initial_root", snapshot.crossedInitialRoot(),
                        "reparse_or_link_count", snapshot.reparseOrLinkCount(),
                        "final_snapshot", snapshot.finalSnapshot());
            }, cancellationProbe);
        } catch (RuntimeException error) {
            com.paicli.diagnostic.StageTrace.event(
                    "CODE_SCAN_END", true,
                    "outcome", "ERROR",
                    "error_type", error.getClass().getName());
            throw error;
        }
        JavaSourceDiscovery.ProgressSnapshot finalProgress = lastProgress.get();
        com.paicli.diagnostic.StageTrace.event(
                "CODE_SCAN_END", true,
                "outcome", "COMPLETED",
                "indexed_java_files", discovery.files().size(),
                "issue_count", discovery.issues().size(),
                "visited_directories", finalProgress == null
                        ? 0 : finalProgress.visitedDirectories(),
                "visited_files", finalProgress == null ? 0 : finalProgress.visitedFiles(),
                "elapsed_ms", finalProgress == null ? 0 : finalProgress.elapsedMillis(),
                "crossed_initial_root", finalProgress != null
                        && finalProgress.crossedInitialRoot(),
                "reparse_or_link_count", finalProgress == null
                        ? 0 : finalProgress.reparseOrLinkCount());
        SymbolLookupService symbols =
                new SymbolLookupService(stateStore.codeIndex, stateStore.codeSearchAudits);
        JavaSourceDiscovery cancellableIndexDiscovery = new JavaSourceDiscovery() {
            @Override
            public DiscoveryResult discover(Path root) {
                return super.discover(root, ProgressListener.NOOP, cancellationProbe);
            }
        };
        CodeIntelligenceService service = new CodeIntelligenceService(
                workspace, new CodeIndexer(cancellableIndexDiscovery,
                        new JavaSourceParser(), stateStore.codeIndex), symbols,
                new DirectCallSiteSearch(
                        stateStore.codeIndex, symbols, stateStore.codeSearchAudits),
                new CodeContextBuilder(stateStore.codeIndex),
                stateStore.codeSearchAudits);

        Set<String> files = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        int contextItems = 0;
        int queries = 0;
        for (String candidate : candidates) {
            if (queries == MAX_QUERIES) break;
            CodeIntelligenceResult result = service.buildContextForClass(candidate);
            queries++;
            result.lookup().classes().forEach(type -> {
                files.add(type.relativePath());
                names.add(type.className());
            });
            if (result.context() != null) {
                contextItems += result.context().sections().size();
            }
        }
        return new CodeFacts(discovery.files().size(), queries, contextItems,
                files.stream().limit(32).toList(),
                names.stream().limit(32).toList(), true);
    }

    private Set<String> candidates(String taskText, List<String> references) {
        Set<String> values = new LinkedHashSet<>();
        for (String reference : references) {
            if (reference.endsWith(".java")) {
                Path name = Path.of(reference).getFileName();
                String file = name == null ? "" : name.toString();
                if (file.endsWith(".java") && file.length() > 5) {
                    values.add(file.substring(0, file.length() - 5));
                }
            }
        }
        Matcher matcher = CLASS_TOKEN.matcher(taskText);
        while (matcher.find() && values.size() < MAX_QUERIES) {
            values.add(matcher.group());
        }
        return values;
    }

    public record CodeFacts(int indexedJavaFiles, int queryCount,
            int contextItemCount, List<String> relativeFiles,
            List<String> symbols, boolean untrusted) {
        public CodeFacts {
            relativeFiles = List.copyOf(relativeFiles);
            symbols = List.copyOf(symbols);
        }
    }
}
