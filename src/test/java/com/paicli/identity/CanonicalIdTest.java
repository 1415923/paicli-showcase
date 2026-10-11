package com.paicli.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalIdTest {
    @Test void everyFamilyGeneratesStrictLowercaseUuidV4Identity() {
        for (EntityFamily family : EntityFamily.values()) {
            CanonicalId id = CanonicalId.newId(family);
            assertEquals(family, CanonicalId.parse(id.value()).family());
            assertEquals(36, id.value().length());
            assertEquals('4', id.value().charAt(4 + 12));
        }
    }
    @Test void rejectsNormalizationAndUnknownFamilies() {
        for (String invalid : List.of("TSK_0123456789abcdef0123456789abcdef",
                "tsk_0123456789ab4def8Abc0123456789ab",
                "tsk_0123456789ab4def8gbc0123456789ab",
                "tsk_01234567-89ab-cdef-0123-456789abcdef",
                " tsk_0123456789abcdef0123456789abcdef",
                "tsk_0123456789ab3def8abc0123456789ab",
                "tsk_0123456789ab4def7abc0123456789ab",
                "xxx_0123456789ab4def8abc0123456789ab",
                "tsk_0123456789ab4def8abc0123456789a",
                "tsk_0123456789ab4def8abc0123456789abc")) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalId.parse(invalid));
        }
    }
    @Test void shortSelectionIsScopedAndAmbiguityIsExplicit() {
        CanonicalId first = CanonicalId.parse("tsk_0123456789ab4abc8def0123456789ab");
        CanonicalId second = CanonicalId.parse("tsk_0123456789ab4def8abc0123456789ab");
        assertInstanceOf(ShortIdResolution.Ambiguous.class, ShortIdResolution.resolve(EntityFamily.TASK, "tsk_0123456789ab", List.of(first, second)));
        assertInstanceOf(ShortIdResolution.NotFound.class, ShortIdResolution.resolve(EntityFamily.TASK, "tsk_ffffffffffff", List.of(first)));
        assertInstanceOf(ShortIdResolution.Invalid.class,
                ShortIdResolution.resolve(EntityFamily.THREAD, "tsk_0123456789ab", List.of(first)));
        assertInstanceOf(ShortIdResolution.Found.class,
                ShortIdResolution.resolve(EntityFamily.TASK,
                        new ShortIdScope("workspace", null), "tsk_0123456789ab4", List.of(first)));
    }

    @Test void planStepIdentityRequiresLogicalOrRevisionColumnContext() {
        CanonicalId id = CanonicalId.newId(EntityFamily.PLAN_STEP);
        assertEquals(PlanStepId.Kind.LOGICAL_STEP,
                new PlanStepId(id, PlanStepId.Kind.LOGICAL_STEP).kind());
        assertThrows(IllegalArgumentException.class,
                () -> new PlanStepId(CanonicalId.newId(EntityFamily.PLAN), PlanStepId.Kind.STEP_REVISION));
    }

    @Test void legacyIdsNeverEnterCanonicalParser() {
        LegacyId legacy = new LegacyId(EntityFamily.TASK, "interactive-123456789012");
        assertEquals(EntityFamily.TASK, legacy.declaredFamily());
        assertThrows(IllegalArgumentException.class, () -> CanonicalId.parse(legacy.value()));
    }
}
