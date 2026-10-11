package com.paicli.interactive.command;

import java.util.List;

@FunctionalInterface
public interface InteractiveDoctorPort {
    List<String> inspect();
    static InteractiveDoctorPort unavailable() {
        return () -> List.of("Doctor is unavailable in this preview context.");
    }
}