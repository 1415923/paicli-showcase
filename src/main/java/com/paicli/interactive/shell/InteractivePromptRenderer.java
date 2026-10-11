package com.paicli.interactive.shell;

import com.paicli.interactive.query.InteractiveQueryFacade;
import com.paicli.interactive.render.InteractiveViewModelRenderer;
import com.paicli.interactive.session.InteractiveSession;

import java.util.Objects;

public final class InteractivePromptRenderer {
    private final InteractiveQueryFacade queries;
    private final InteractiveViewModelRenderer renderer = new InteractiveViewModelRenderer();

    /** Runtime-only compatibility mode; no durable read is attempted by this constructor. */
    public InteractivePromptRenderer() { this.queries = null; }

    public InteractivePromptRenderer(InteractiveQueryFacade queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    public String primary() { return "paicli> "; }

    public String primary(InteractiveSession.Snapshot session) {
        if (session == null || queries == null) return primary();
        return prompt(queries.readViewModel(session), false, null);
    }

    public String running(String phase) {
        if (phase == null || phase.isBlank() || "IDLE".equalsIgnoreCase(phase)) {
            return "paicli [running]> ";
        }
        String safe = phase.replaceAll("[^A-Za-z0-9_-]", "_");
        return "paicli [running: " + safe + "]> ";
    }

    public String running(InteractiveSession.Snapshot session, String phase) {
        if (session == null || queries == null) return running(phase);
        return prompt(queries.readViewModel(session), true, phase);
    }
    public String secondary() { return "....> "; }

    private String prompt(com.paicli.interactive.query.ViewModelReadResult result,
            boolean running, String phase) {
        return switch (result.state()) {
            case PRESENT -> renderer.prompt(result.value());
            case ABSENT -> running ? running(phase) : primary();
            case UNAVAILABLE -> "paicli [status-unavailable]> ";
            case INTEGRITY_FAILURE -> "paicli [integrity-blocked]> ";
        };
    }
    public String steering(com.paicli.interactive.steering.SteeringConfirmationRequest request){
        return "steering confirmation ["+request.steeringId().substring(0,16)+"]>\n"
                +"Classify [n] non-material / [m] material / [r] reject / [c] cancel: ";
    }
    public String followUp(com.paicli.interactive.followup.FollowUpConfirmationRequest request){
        return "follow-up "+request.kind().toLowerCase()+" ["+request.capsuleId().substring(0,16)+"]>\n"
                +request.boundedPreview()+"\n"
                +(request.kind().equals("INTENT")
                ?"Choose [c] continue current Thread / [n] new Thread / [x] cancel: "
                :"Manifest authority changed. [y] accept current Manifest / [x] cancel: ");
    }
    public String approval(com.paicli.interactive.approval.InteractiveApprovalRequest request) {
        if(request.kind()==com.paicli.interactive.approval.InteractiveApprovalRequest.Kind.PLAN){
            String revision=request.planRevisionId();
            if(revision.length()>16)revision=revision.substring(0,16);
            return "approval [structured plan · revision "+revision+" · "
                    +request.planStepCount()+" steps]>\nApprove this immutable Plan revision?"
                    +" (y=yes, n=reject, d=details, c=cancel): ";
        }
        String target = request.relativePath() == null ? ""
                : " " + java.nio.file.Path.of(request.relativePath()).getFileName();
        String action = switch (request.kind()) {
            case PLAN -> throw new IllegalStateException("Plan approval handled above");
            case EDIT -> "edit" + target;
            case NEW_FILE -> "new file" + target;
            case COMMAND -> "command";
            case GIT_READ -> "git read";
            case OTHER -> request.toolName();
        };
        String question = switch (request.kind()) {
            case PLAN -> throw new IllegalStateException("Plan approval handled above");
            case EDIT -> "Apply?";
            case NEW_FILE -> "Create?";
            case COMMAND -> "Run command?";
            case GIT_READ -> "Run Git read?";
            case OTHER -> "Allow?";
        };
        return "approval [" + action + "]>\n" + question
                + " (y=yes, n=reject, d=diff"
                + (request.kind() == com.paicli.interactive.approval.InteractiveApprovalRequest.Kind.EDIT
                    ? ", a=all-safe" : "")
                + ", c=cancel): ";
    }
}
