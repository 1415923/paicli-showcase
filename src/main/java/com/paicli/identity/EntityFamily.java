package com.paicli.identity;

import java.util.Arrays;

/** R1 canonical identity families. Prefixes are persisted authority, not display decoration. */
public enum EntityFamily {
    THREAD("thr_"), TASK("tsk_"), RUN("run_"), GENERATION("gen_"), CONTEXT("ctx_"), CAPSULE("cap_"),
    PLAN("pln_"), PLAN_STEP("stp_"), STEERING("str_"), OCCURRENCE("occ_");

    private final String prefix;
    EntityFamily(String prefix) { this.prefix = prefix; }
    public String prefix() { return prefix; }
    public static EntityFamily fromPrefix(String prefix) {
        return Arrays.stream(values()).filter(value -> value.prefix.equals(prefix)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown canonical ID prefix"));
    }
}
