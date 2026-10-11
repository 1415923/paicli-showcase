package com.paicli.skill;

import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.state.SkillSelectionStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects relevant skills deterministically; no model or authorization API is used. */
public final class SkillSelector {

    private static final int MAX_CONTEXT_EXCERPT_BYTES = 2048;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final SkillSelectionStore store;
    private final PersistenceSanitizer sanitizer;
    private FencedExecutionGuard executionGuard;

    public void bindExecutionGuard(FencedExecutionGuard guard){this.executionGuard=guard;}

    public SelectionResult selectExact(String taskId,String runId,String stage,String occurrence,
            List<SkillDefinition> skills,String reason)throws SQLException {
        String id=UUID.randomUUID().toString();
        List<SelectedSkill> selected=new ArrayList<>();
        List<SkillSelectionStore.SelectedSkillInput> inputs=new ArrayList<>();
        for(int index=0;index<skills.size();index++){
            SkillDefinition skill=skills.get(index);int rank=index+1;
            selected.add(new SelectedSkill(skill,rank,1,reason));
            inputs.add(new SkillSelectionStore.SelectedSkillInput(UUID.randomUUID().toString(),
                    skill.skillLoadId(),skill.name(),rank,1,reason,
                    sanitizer.sanitizeAndTruncate(skill.content(),MAX_CONTEXT_EXCERPT_BYTES)));
        }
        String hash=com.paicli.skill.SkillPackageFiles.hash((reason+skills.stream()
                .map(SkillDefinition::contentSha256).toList()).getBytes(StandardCharsets.UTF_8));
        if(executionGuard!=null)executionGuard.recordSkillSelection(id,stage,occurrence,hash,inputs);
        else store.recordCompleted(id,runId,taskId,stage,occurrence,hash,inputs);
        return new SelectionResult(id,List.copyOf(selected));
    }

    public SkillSelector(SkillSelectionStore store,
                         PersistenceSanitizer sanitizer) {
        this(store,sanitizer,null);
    }

    public SkillSelector(SkillSelectionStore store,PersistenceSanitizer sanitizer,
            FencedExecutionGuard executionGuard){
        this.store = Objects.requireNonNull(store, "store");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.executionGuard=executionGuard;
    }

    public SelectionResult select(String taskId, String runId, String stage,
                                  String occurrenceKey, String goal,
                                  String plan, String repairContext,
                                  List<SkillDefinition> skills,
                                  int maxSelectedSkills) throws SQLException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(occurrenceKey, "occurrenceKey");
        Objects.requireNonNull(skills, "skills");
        if (maxSelectedSkills < 1 || maxSelectedSkills > 10) {
            throw new IllegalArgumentException(
                    "maxSelectedSkills must be between 1 and 10");
        }

        String input = joinInput(goal, plan, repairContext);
        String normalizedInput = input.toLowerCase(Locale.ROOT);
        Set<String> inputTokens = tokens(normalizedInput);
        List<ScoredSkill> scored = skills.stream()
                .map(skill -> score(skill, normalizedInput, inputTokens))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredSkill::score).reversed()
                        .thenComparing(candidate ->
                                candidate.skill().name().toLowerCase(Locale.ROOT))
                        .thenComparing(candidate ->
                                candidate.skill().relativePath()))
                .limit(maxSelectedSkills)
                .toList();

        String selectionId = UUID.randomUUID().toString();
        List<SelectedSkill> selected = new ArrayList<>();
        List<SkillSelectionStore.SelectedSkillInput> audit = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            ScoredSkill candidate = scored.get(index);
            int rank = index + 1;
            String selectedSkillId = UUID.randomUUID().toString();
            String reason = sanitizer.sanitizeAndTruncate(
                    candidate.reason(), 512);
            String excerpt = sanitizer.sanitizeAndTruncate(
                    candidate.skill().content(), MAX_CONTEXT_EXCERPT_BYTES);
            selected.add(new SelectedSkill(
                    candidate.skill(), rank, candidate.score(), reason));
            audit.add(new SkillSelectionStore.SelectedSkillInput(
                    selectedSkillId,
                    candidate.skill().skillLoadId(),
                    candidate.skill().name(),
                    rank,
                    candidate.score(),
                    reason,
                    excerpt
            ));
        }
        if(executionGuard!=null)executionGuard.recordSkillSelection(selectionId,stage,
                occurrenceKey,sha256(input),audit);
        else store.recordCompleted(selectionId, runId, taskId, stage,
                occurrenceKey, sha256(input), audit);
        return new SelectionResult(selectionId, List.copyOf(selected));
    }

    private ScoredSkill score(SkillDefinition skill, String input,
                              Set<String> inputTokens) {
        double score = 0;
        List<String> matchedTriggers = new ArrayList<>();
        for (String trigger : skill.triggers()) {
            String normalized = trigger.toLowerCase(Locale.ROOT);
            if (input.contains(normalized)) {
                score += 10;
                matchedTriggers.add(trigger);
            } else {
                Set<String> triggerTokens = tokens(normalized);
                long matches = triggerTokens.stream().filter(inputTokens::contains).count();
                if (matches > 0) {
                    score += matches * 3;
                    matchedTriggers.add(trigger);
                }
            }
        }
        String normalizedName = skill.name().toLowerCase(Locale.ROOT);
        if (input.contains(normalizedName)) {
            score += 5;
        }
        long descriptionMatches = tokens(skill.description().toLowerCase(Locale.ROOT))
                .stream().filter(inputTokens::contains).count();
        score += Math.min(descriptionMatches, 5);

        String reason = matchedTriggers.isEmpty()
                ? "Matched skill name or description"
                : "Matched triggers: " + String.join(", ", matchedTriggers);
        return new ScoredSkill(skill, score, reason);
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private String joinInput(String goal, String plan, String repairContext) {
        return String.join("\n",
                goal != null ? goal : "",
                plan != null ? plan : "",
                repairContext != null ? repairContext : "");
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record ScoredSkill(SkillDefinition skill, double score,
                               String reason) {
    }

    public record SelectedSkill(
            SkillDefinition skill,
            int rank,
            double score,
            String reason
    ) {
    }

    public record SelectionResult(
            String selectionId,
            List<SelectedSkill> selectedSkills
    ) {
        public SelectionResult {
            selectedSkills = List.copyOf(selectedSkills);
        }
    }
}
