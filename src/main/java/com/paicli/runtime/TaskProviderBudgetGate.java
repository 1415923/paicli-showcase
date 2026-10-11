package com.paicli.runtime;

import java.util.Objects;

/**
 * Task-total admission gate for a new logical Provider call.
 *
 * <p>Token usage is authoritative only after a Provider response is persisted. Therefore the
 * token bounds are post-call fuses: a call admitted while cumulative usage is below the bound may
 * cross it, but no later Provider call may be admitted. This gate does not authorize or bypass any
 * Tool operation returned by the already-admitted call.</p>
 */
@FunctionalInterface
public interface TaskProviderBudgetGate {
    TaskProviderBudgetGate ALLOW_ALL = (taskId, runId) -> Admission.allowed();

    Admission evaluate(String taskId, String runId);

    record Admission(boolean admitted, String code) {
        public Admission {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Provider budget admission code is required");
            }
            if (admitted && !"ADMITTED".equals(code)) {
                throw new IllegalArgumentException("Admitted Provider budget must use ADMITTED");
            }
        }

        public static Admission allowed() {
            return new Admission(true, "ADMITTED");
        }

        public static Admission rejected(String code) {
            return new Admission(false, Objects.requireNonNull(code, "code"));
        }
    }

    final class AdmissionException extends RuntimeException {
        private final String code;

        public AdmissionException(String code) {
            this(code, null);
        }

        public AdmissionException(String code, Throwable cause) {
            super(Objects.requireNonNull(code, "code"), cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
