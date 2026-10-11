package com.paicli.provider;

/** Independent capabilities: support for each component does not qualify their combination. */
public record OutputCapabilities(Support json, Support tools, Support jsonWithTools) {
    public enum Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
    public static final OutputCapabilities UNKNOWN = new OutputCapabilities(Support.UNKNOWN, Support.UNKNOWN, Support.UNKNOWN);
    public void require(OutputRequirement requirement, boolean hasTools) {
        if (!requirement.nativeJson()) return;
        if (json != Support.SUPPORTED || (hasTools && tools != Support.SUPPORTED))
            throw new IllegalArgumentException("OUTPUT_NATIVE_CAPABILITY_UNAVAILABLE");
        if (hasTools && jsonWithTools != Support.SUPPORTED
                && !(jsonWithTools == Support.UNKNOWN
                    && requirement.mode() == OutputRequirement.Mode.DIAGNOSTIC_NATIVE_CANDIDATE))
            throw new IllegalArgumentException("OUTPUT_JSON_WITH_TOOLS_NOT_QUALIFIED");
    }
}
