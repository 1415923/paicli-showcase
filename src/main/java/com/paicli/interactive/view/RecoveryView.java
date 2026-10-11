package com.paicli.interactive.view;

import java.util.List;

public record RecoveryView(String status, boolean eligible, List<String> reasons) {
    public RecoveryView { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
}
