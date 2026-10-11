package com.paicli.interactive.approval;

import java.util.List;

public record ApprovalInteractionResult(boolean resolved, boolean cancelTask, List<String> lines) {
    public ApprovalInteractionResult { lines = List.copyOf(lines); }
}
