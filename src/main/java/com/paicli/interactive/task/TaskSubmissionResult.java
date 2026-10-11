package com.paicli.interactive.task;

public record TaskSubmissionResult(Status status, String code, String message) {
    public enum Status {
        PREPARING, ACCEPTED, REJECTED, UNAVAILABLE, COMPLETED, COMPLETED_VERIFIED,
        COMPLETED_UNVERIFIED, INCOMPLETE, FAILED, CANCELLED
    }

    public TaskSubmissionResult {
        if (status == null || !safeCode(code) || message == null || message.length() > 512) {
            throw new IllegalArgumentException("invalid task submission result");
        }
        message = message.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
    }

    public static TaskSubmissionResult accepted(String message) {
        return new TaskSubmissionResult(Status.ACCEPTED, "ACCEPTED", message);
    }

    public static TaskSubmissionResult preparing() {
        return new TaskSubmissionResult(Status.PREPARING, "PREPARING", "Preparing...");
    }

    public static TaskSubmissionResult completed(String message) {
        return new TaskSubmissionResult(Status.COMPLETED, "COMPLETED", message);
    }

    public static TaskSubmissionResult failed(String code, String message) {
        return new TaskSubmissionResult(Status.FAILED, code, message);
    }

    public static TaskSubmissionResult cancelled(String message) {
        return new TaskSubmissionResult(Status.CANCELLED, "CANCELLED", message);
    }

    public static TaskSubmissionResult rejected(String code, String message) {
        return new TaskSubmissionResult(Status.REJECTED, code, message);
    }

    public static TaskSubmissionResult unavailable(String code, String message) {
        return new TaskSubmissionResult(Status.UNAVAILABLE, code, message);
    }

    private static boolean safeCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}");
    }
}
