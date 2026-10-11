package com.paicli.demo.workflow;

import com.paicli.demo.baseline.CoreDemoBaselineResult;
import java.util.List;

public record CoreDemoRewindResult(
        String rewindOccurrenceKey,
        List<String> snapshotIdsInRewindOrder,
        String finalTreeHash,
        String intermediateTreeHash,
        String rewindTreeHash,
        CoreDemoBaselineResult postRewindBaseline,
        boolean alreadyRewound) {
    public CoreDemoRewindResult {
        snapshotIdsInRewindOrder=List.copyOf(snapshotIdsInRewindOrder);
    }
}