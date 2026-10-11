package com.paicli.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class OwnedProcessRegistry {
    private final Set<Process> owned = ConcurrentHashMap.newKeySet();

    public Registration register(Process process) {
        if (process == null) throw new IllegalArgumentException("process is required");
        owned.add(process);
        return () -> owned.remove(process);
    }

    public int requestTerminateAll() {
        int requested = 0;
        for (Process process : Set.copyOf(owned)) {
            if (!process.isAlive()) {
                owned.remove(process);
                continue;
            }
            try {
                process.descendants().filter(ProcessHandle::isAlive)
                        .forEach(ProcessHandle::destroy);
            } catch (UnsupportedOperationException ignored) {
                // The owned root process remains sufficient for bounded fallback.
            }
            process.destroy();
            requested++;
        }
        return requested;
    }
    public int forceTerminateAll() {
        int terminated = 0;
        for (Process process : Set.copyOf(owned)) {
            if (terminate(process)) terminated++;
        }
        return terminated;
    }

    public int liveCount() {
        return (int) owned.stream().filter(Process::isAlive).count();
    }

    private boolean terminate(Process process) {
        if (!process.isAlive()) {
            owned.remove(process);
            return false;
        }
        java.util.List<ProcessHandle> descendants;
        try { descendants = process.descendants().toList(); }
        catch (UnsupportedOperationException ignored) { descendants = java.util.List.of(); }
        descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        process.destroy();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        owned.remove(process);
        return true;
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }
}
