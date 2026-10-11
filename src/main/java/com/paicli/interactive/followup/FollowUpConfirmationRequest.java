package com.paicli.interactive.followup;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

/** Frozen terminal-read ownership for one durable Follow-up confirmation. */
public record FollowUpConfirmationRequest(String capsuleId,String kind,long candidateVersion,
                                          String taskId,String boundedPreview){
    public FollowUpConfirmationRequest{
        CanonicalId parsed;
        try{parsed=CanonicalId.parse(capsuleId);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid Follow-up confirmation",invalid);}
        CanonicalId task;
        try{task=CanonicalId.parse(taskId);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid Follow-up confirmation",invalid);}
        if(parsed.family()!=EntityFamily.CAPSULE||task.family()!=EntityFamily.TASK||kind==null||candidateVersion<0
                ||boundedPreview==null||boundedPreview.length()>512)
            throw new IllegalArgumentException("Invalid Follow-up confirmation");
    }
}
