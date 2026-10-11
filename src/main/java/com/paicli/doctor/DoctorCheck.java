package com.paicli.doctor;

import java.util.Objects;

public record DoctorCheck(String name, DoctorStatus status, String detail) {
    public DoctorCheck {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }
}