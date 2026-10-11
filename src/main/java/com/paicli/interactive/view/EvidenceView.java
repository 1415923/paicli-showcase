package com.paicli.interactive.view;

public record EvidenceView(int total, int succeeded, int failed, int unknown,
        boolean integrityValid, boolean completionDecided) { }
