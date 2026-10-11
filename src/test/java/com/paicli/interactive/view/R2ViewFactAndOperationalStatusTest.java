package com.paicli.interactive.view;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class R2ViewFactAndOperationalStatusTest {
    private static final Instant AT = Instant.parse("2026-07-20T00:00:00Z");

    @Test void unavailableUnknownNotLoadedAndNotExposedAreNeverAbsent() {
        for (ViewFact.State state : List.of(ViewFact.State.UNKNOWN,
                ViewFact.State.UNAVAILABLE, ViewFact.State.NOT_LOADED,
                ViewFact.State.NOT_EXPOSED, ViewFact.State.NOT_APPLICABLE,
                ViewFact.State.STALE)) {
            ViewFact<String> fact = state == ViewFact.State.UNAVAILABLE
                    ? ViewFact.unavailable(ViewFact.Source.DURABLE, AT, "safe-ref")
                    : ViewFact.state(state, ViewFact.Source.DURABLE, AT);
            assertNotEquals(ViewFact.State.ABSENT, fact.state());
            assertFalse(fact.isPresent());
        }
        assertEquals(0, ViewFact.present(0, ViewFact.Source.DURABLE, AT).value());
        assertThrows(IllegalArgumentException.class, () -> new ViewFact<>(
                ViewFact.State.ABSENT, "masked error", ViewFact.Source.DURABLE,
                AT, ViewFact.Freshness.UNKNOWN, null));
    }

    @Test void firstMatchOrderIsStableAcrossEveryOperationalStatus() {
        List<OperationalStatusView.Status> expected = List.of(
                OperationalStatusView.Status.BLOCKED_INTEGRITY,
                OperationalStatusView.Status.WAITING_FOR_CONFIRMATION,
                OperationalStatusView.Status.RECOVERY_REQUIRED,
                OperationalStatusView.Status.CHILD_INITIAL_RUN_PENDING,
                OperationalStatusView.Status.EXECUTING,
                OperationalStatusView.Status.WAITING_FOR_APPROVAL,
                OperationalStatusView.Status.VERIFYING,
                OperationalStatusView.Status.REPAIRING,
                OperationalStatusView.Status.FOLLOW_UP_PENDING,
                OperationalStatusView.Status.COMPLETED,
                OperationalStatusView.Status.PROVIDER_DEGRADED,
                OperationalStatusView.Status.READY,
                OperationalStatusView.Status.IDLE,
                OperationalStatusView.Status.FAILED);
        for (int first = 0; first < expected.size(); first++) {
            boolean[] flags = new boolean[13];
            for (int index = first; index < flags.length; index++) flags[index] = true;
            assertEquals(expected.get(first), OperationalStatusPrecedence.select(facts(flags)));
        }
    }

    @Test void followUpAndChildPendingWinBeforeCompleted() {
        assertEquals(OperationalStatusView.Status.CHILD_INITIAL_RUN_PENDING,
                OperationalStatusPrecedence.select(new OperationalStatusPrecedence.Facts(
                        false, false, false, true, false, false, false, false,
                        true, true, false, false, false)));
        assertEquals(OperationalStatusView.Status.FOLLOW_UP_PENDING,
                OperationalStatusPrecedence.select(new OperationalStatusPrecedence.Facts(
                        false, false, false, false, false, false, false, false,
                        true, true, false, false, false)));
    }

    private static OperationalStatusPrecedence.Facts facts(boolean[] value) {
        return new OperationalStatusPrecedence.Facts(value[0], value[1], value[2],
                value[3], value[4], value[5], value[6], value[7], value[8],
                value[9], value[10], value[11], value[12]);
    }
}
