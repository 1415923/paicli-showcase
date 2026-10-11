package com.paicli.doctor;

import java.util.List;

public record DoctorReport(List<DoctorCheck> checks, DoctorStatus overall) {
    public DoctorReport {
        checks = List.copyOf(checks);
    }

    public int exitCode() {
        return overall == DoctorStatus.FAIL ? 1 : 0;
    }
}