package com.paicli.doctor;

import java.io.PrintWriter;
import java.nio.file.Path;

public final class DoctorCommand {
    private final DoctorService service;

    public DoctorCommand(Path workspace) {
        this.service = new DoctorService(workspace);
    }

    public DoctorCommand(DoctorService service) {
        this.service = service;
    }

    public int execute(PrintWriter out) {
        DoctorReport report = service.inspect();
        out.println("PaiCLI Doctor");
        out.println();
        for (DoctorCheck check : report.checks()) {
            out.printf("%-20s %-4s", check.name(), check.status());
            if (!check.detail().isBlank()) out.print("  " + check.detail());
            out.println();
        }
        out.println();
        out.println("Overall: " + report.overall());
        out.flush();
        return report.exitCode();
    }
}
