package com.paicli.runtime;

import com.paicli.state.FollowUpModels.Intent;
import com.paicli.state.FollowUpModels.IntentClassification;

import java.util.Locale;

/** Provider-free terminal-input classifier. Explicit commands are routed before this classifier. */
public final class FollowUpClassifier {
    public static final String VERSION="r1e-followup-v1";
    public IntentClassification classify(String sanitized){
        if(sanitized==null||sanitized.isBlank())return result(Intent.REJECT,"EMPTY_INPUT");
        String value=sanitized.strip().toLowerCase(Locale.ROOT);
        if(value.startsWith("/new")||value.startsWith("new topic:")||value.startsWith("新话题")
                ||value.startsWith("另一个问题"))return result(Intent.CREATE_NEW_THREAD,"EXPLICIT_NEW_TOPIC");
        if(value.startsWith("继续")||value.startsWith("接着")||value.startsWith("follow up")
                ||value.startsWith("continue")||value.startsWith("基于上面")||value.startsWith("关于刚才"))
            return result(Intent.CONTINUE_CURRENT_THREAD,"CONTINUATION_MARKER");
        return result(Intent.UNKNOWN,"AMBIGUOUS_TERMINAL_INPUT");
    }
    private static IntentClassification result(Intent intent,String reason){
        return new IntentClassification(intent,VERSION,reason);
    }
}
