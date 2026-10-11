package com.paicli.runtime;

import java.io.IOException;
import java.nio.file.Path;

/** Test-only access to the existing package-private H5 authority. */
public final class WorkspaceCandidateDigestTestAccess {
    private WorkspaceCandidateDigestTestAccess() { }

    public static String kind() {
        return WorkspaceCandidateDigest.KIND;
    }

    public static String compute(Path workspace) throws IOException {
        return new WorkspaceCandidateDigest(workspace).compute().digest();
    }
}
