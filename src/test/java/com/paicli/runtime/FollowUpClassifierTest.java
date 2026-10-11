package com.paicli.runtime;

import com.paicli.state.FollowUpModels.Intent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FollowUpClassifierTest {
    private final FollowUpClassifier classifier=new FollowUpClassifier();
    @Test void classificationIsProviderFreeDeterministicAndUnknownFailsClosed(){
        assertEquals(Intent.CONTINUE_CURRENT_THREAD,classifier.classify("继续修复刚才的问题").intent());
        assertEquals(Intent.CREATE_NEW_THREAD,classifier.classify("new topic: unrelated").intent());
        assertEquals(Intent.UNKNOWN,classifier.classify("please inspect this").intent());
        assertEquals(Intent.REJECT,classifier.classify("  ").intent());
        assertEquals(classifier.classify("continue the task"),classifier.classify("continue the task"));
    }
}
