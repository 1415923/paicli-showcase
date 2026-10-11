package com.paicli.provider;

import java.util.Objects;

/** Runtime-owned output policy. Text supplied by users or tools cannot select this policy. */
public record OutputRequirement(Contract contract, Mode mode) {
    public enum Contract { LEGACY, PAICLI_USER_ANSWER_V1 }
    public enum Mode { LEGACY, PROMPT_ONLY, NATIVE_REQUIRED, DIAGNOSTIC_NATIVE_CANDIDATE }
    public static final OutputRequirement LEGACY = new OutputRequirement(Contract.LEGACY, Mode.LEGACY);

    public OutputRequirement {
        Objects.requireNonNull(contract); Objects.requireNonNull(mode);
        if ((contract == Contract.LEGACY) != (mode == Mode.LEGACY))
            throw new IllegalArgumentException("OUTPUT_REQUIREMENT_CONTRACT_MODE_MISMATCH");
    }
    public static OutputRequirement managed(Mode mode) {
        return new OutputRequirement(Contract.PAICLI_USER_ANSWER_V1, mode);
    }
    public boolean managed() { return contract != Contract.LEGACY; }
    public boolean nativeJson() { return mode == Mode.NATIVE_REQUIRED || mode == Mode.DIAGNOSTIC_NATIVE_CANDIDATE; }
    public String identity() { return "OUTPUT_REQUIREMENT_V1:" + contract + ":" + mode; }
}
