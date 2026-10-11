package com.paicli.state;

import java.util.List;

/** Additive, non-authority Provider call telemetry schema. */
final class V25Migration {
    private V25Migration() { }

    static List<String> statements() {
        return ProviderTelemetrySchemaContract.statements();
    }
}
