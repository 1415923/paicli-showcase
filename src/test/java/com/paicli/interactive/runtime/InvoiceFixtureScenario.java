package com.paicli.interactive.runtime;

import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Scripted zero-model exercise of the exact task driver and approval responder. */
final class InvoiceFixtureScenario implements ModelProvider {
    private int step;
    public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> definitions) {
        if(step>=7) return ModelResponse.finalAnswer("Preflight browser operations recorded; no model quality is claimed.");
        String[] names={"new_page","take_snapshot","fill","click","take_snapshot","list_console_messages","list_network_requests"};
        String name=names[step++];Map<String,Object> args=new HashMap<>();
        String history=String.join("\n",messages.stream().filter(m->m.type()==AgentMessage.MessageType.OBSERVATION)
                .map(m->Objects.toString(m.content(),"")).toList());
        if(name.equals("new_page"))args.put("url",InvoiceFixtureApproval.URL);
        else {
            var page=Pattern.compile("(?m)^(\\d+): D7 invoice filter \\(http://127\\.0\\.0\\.1:18797/\\) \\[selected\\]$").matcher(history);
            assertTrue(page.find(),"Fixture page absent");args.put("pageId",Integer.parseInt(page.group(1)));
            if(name.equals("fill")||name.equals("click")) {
                String role=name.equals("fill")?"textbox \"Filter\"":"button \"Apply\"";
                var element=Pattern.compile("uid=(\\S+) "+role).matcher(history);String uid=null;
                while(element.find())uid=element.group(1);assertNotNull(uid,"Fixture element absent");args.put("uid",uid);
                if(name.equals("fill"))args.put("value","INV-2047");
            }
        }
        String exposed=definitions.stream().map(ToolDefinition::name).filter(n->n.startsWith("mcp_chrome_"+name+"_")).findFirst().orElseThrow();
        return ModelResponse.toolCallResponse("Exercise fixture approval",new ToolCall(exposed,args));
    }
}
