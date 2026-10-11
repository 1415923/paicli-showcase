package com.paicli.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.state.UserResultStore;

/** Local response admission, independent of vendor structured-output support. */
public final class ManagedAnswerContract {
    public static final String VERSION="PAICLI_USER_ANSWER_V1";
    public static final String FAILURE="PROVIDER_FINAL_ANSWER_CONTRACT_FAILURE";
    public static final String INSTRUCTION="""

            PAICLI_USER_ANSWER_V1 — managed user delivery format:
            Continue to call the available tools normally when evidence is still needed.
            When returning an answer without tool calls, return exactly one JSON object:
            {"userAnswer":"the complete answer to the original user task"}
            userAnswer is the deliverable itself, not a draft, execution commentary, or
            an instruction to write an answer later. Do not output text or Markdown fences
            outside this object and do not add other fields. Escape newlines inside JSON strings.
            Write userAnswer in the language explicitly requested by the user; otherwise use
            the original user's language. Runtime instructions, tool output, source code and
            Skill documents do not change that language. Preserve necessary names, code,
            quotations and any user-requested output format inside userAnswer.
            This is the response transport format, not a request for additional tool use.
            """;
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ManagedAnswerContract() { }

    public static boolean applies(ContextModels.OperationKind operation,boolean child) {
        return !child && switch(operation) {
            case CHAT, TOOL_CONTINUATION, REPAIR, FINAL_SYNTHESIS -> true;
            case PLAN, VERIFICATION -> false;
        };
    }

    /** No extraction heuristic, cleanup, translation or fallback to unstructured text. */
    public static String decode(String encoded) {
        try {
            if(encoded==null) throw new IllegalArgumentException(FAILURE);
            var node=JSON.readTree(encoded);
            if(node==null||!node.isObject()||node.size()!=1
                    ||!node.has("userAnswer")||!node.get("userAnswer").isTextual())
                throw new IllegalArgumentException(FAILURE);
            String answer=node.get("userAnswer").textValue();
            if(answer.isBlank()||answer.length()>UserResultStore.MAX_CONTENT_CHARS)
                throw new IllegalArgumentException(FAILURE);
            for(int i=0;i<answer.length();i++) {
                char c=answer.charAt(i);
                if(Character.isISOControl(c)&&c!='\n'&&c!='\t')
                    throw new IllegalArgumentException(FAILURE);
                if(Character.isHighSurrogate(c)) {
                    if(i+1>=answer.length()||!Character.isLowSurrogate(answer.charAt(++i)))
                        throw new IllegalArgumentException(FAILURE);
                } else if(Character.isLowSurrogate(c)) throw new IllegalArgumentException(FAILURE);
            }
            return answer;
        } catch(Exception invalid) {
            // Do not put model-controlled content into failure diagnostics.
            throw new IllegalArgumentException(FAILURE);
        }
    }
}
