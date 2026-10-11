package com.paicli.runtime;

import java.util.OptionalInt;

/** Narrow read-only contract for a failure carrying an exact AgentLoop invocation coordinate. */
public interface ObservedIterationFailure {
    OptionalInt observedIteration();
}
