package com.paicli.identity;

import java.util.List;

/** Type/scope callers supply candidates; this class never probes stores to guess a type. */
public sealed interface ShortIdResolution permits ShortIdResolution.Found, ShortIdResolution.NotFound,
        ShortIdResolution.Ambiguous, ShortIdResolution.Invalid {
    static ShortIdResolution resolve(EntityFamily family, ShortIdScope scope, String selector,
                                     List<CanonicalId> scopedCandidates) {
        if (family == null || scope == null || selector == null || selector.length() < 16
                || selector.length() > 35 || !selector.startsWith(family.prefix())
                || !selector.substring(4).matches("[0-9a-f]{12,31}")) {
            return new Invalid("INVALID_SHORT_ID");
        }
        List<CanonicalId> matches = scopedCandidates.stream().filter(id -> id.family() == family && id.value().startsWith(selector)).toList();
        return matches.isEmpty() ? new NotFound() : matches.size() == 1 ? new Found(matches.getFirst()) : new Ambiguous(matches.size());
    }
    static ShortIdResolution resolve(EntityFamily family, String selector,
                                     List<CanonicalId> scopedCandidates) {
        return resolve(family, ShortIdScope.workspace("default"), selector, scopedCandidates);
    }
    record Found(CanonicalId id) implements ShortIdResolution { }
    record NotFound() implements ShortIdResolution { }
    record Ambiguous(int matchCount) implements ShortIdResolution { }
    record Invalid(String reason) implements ShortIdResolution { }
}
