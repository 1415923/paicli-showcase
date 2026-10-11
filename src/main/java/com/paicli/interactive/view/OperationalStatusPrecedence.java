package com.paicli.interactive.view;

/** Pure first-match resolver; it grants no authority and performs no I/O. */
final class OperationalStatusPrecedence {
    private OperationalStatusPrecedence() { }

    static OperationalStatusView.Status select(Facts fact) {
        if (fact.integrityBlocked()) return OperationalStatusView.Status.BLOCKED_INTEGRITY;
        if (fact.confirmationPending()) return OperationalStatusView.Status.WAITING_FOR_CONFIRMATION;
        if (fact.recoveryRequired()) return OperationalStatusView.Status.RECOVERY_REQUIRED;
        if (fact.waitingForUser()) return OperationalStatusView.Status.WAITING_FOR_USER;
        if (fact.childInitialRunPending()) return OperationalStatusView.Status.CHILD_INITIAL_RUN_PENDING;
        if (fact.executing()) return OperationalStatusView.Status.EXECUTING;
        if (fact.approvalPending()) return OperationalStatusView.Status.WAITING_FOR_APPROVAL;
        if (fact.verifying()) return OperationalStatusView.Status.VERIFYING;
        if (fact.repairing()) return OperationalStatusView.Status.REPAIRING;
        if (fact.followUpPending()) return OperationalStatusView.Status.FOLLOW_UP_PENDING;
        if (fact.completed()) return OperationalStatusView.Status.COMPLETED;
        if (fact.providerRequiredAndDegraded()) return OperationalStatusView.Status.PROVIDER_DEGRADED;
        if (fact.ready()) return OperationalStatusView.Status.READY;
        if (fact.idle()) return OperationalStatusView.Status.IDLE;
        return OperationalStatusView.Status.FAILED;
    }

    record Facts(boolean integrityBlocked, boolean confirmationPending,
            boolean recoveryRequired, boolean waitingForUser, boolean childInitialRunPending,
            boolean executing, boolean approvalPending, boolean verifying,
            boolean repairing, boolean followUpPending, boolean completed,
            boolean providerRequiredAndDegraded, boolean ready, boolean idle) {
        Facts(boolean integrityBlocked, boolean confirmationPending,
                boolean recoveryRequired, boolean childInitialRunPending,
                boolean executing, boolean approvalPending, boolean verifying,
                boolean repairing, boolean followUpPending, boolean completed,
                boolean providerRequiredAndDegraded, boolean ready, boolean idle) {
            this(integrityBlocked,confirmationPending,recoveryRequired,false,
                    childInitialRunPending,executing,approvalPending,verifying,repairing,
                    followUpPending,completed,providerRequiredAndDegraded,ready,idle);
        }
    }
}
