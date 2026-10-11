package com.paicli.interactive.view;

import java.util.Objects;

/** Immutable identity plus display profile for one projection lifecycle. */
public record TaskProjectionKey(
        SelectedTaskIdentity identity,
        TaskProjectionProfile profile) {
    public TaskProjectionKey {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(profile);
    }
}
