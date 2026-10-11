package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.regex.Pattern;

/** Test-driver responder for the explicitly authorized isolated invoice fixture only.
 * Production ApprovalPolicy and its one-request grant remain in charge. */
final class InvoiceFixtureApproval {
    static final String POLICY = "loopback-invoice-v1";
    static final String URL = "http://127.0.0.1:18797";
    private static final ObjectMapper JSON = new ObjectMapper();
    record Observation(String tool, String output) { }

    static boolean allows(Path workspace, String taskId, InteractiveApprovalRequest request) throws Exception {
        if (!taskId.equals(request.taskId())) return false;
        try (var db=DriverManager.getConnection("jdbc:sqlite:file:"
                +workspace.resolve(".paicli/state/paicli.db").toUri().getRawPath()+"?mode=ro")) {
            JsonNode call=null;
            try(var q=db.prepareStatement("SELECT tool_call_json FROM events WHERE task_id=? AND iteration=? AND type='ACTION' ORDER BY id DESC LIMIT 1")) {
                q.setString(1,taskId);q.setInt(2,request.iteration());
                try(var r=q.executeQuery()){if(r.next())call=JSON.readTree(r.getString(1));}
            }
            var observations=new ArrayList<Observation>();
            try(var q=db.prepareStatement("SELECT tool_name,result_output FROM tool_calls WHERE task_id=? AND result_success=1 ORDER BY id")) {
                q.setString(1,taskId);
                try(var r=q.executeQuery()){while(r.next())observations.add(new Observation(r.getString(1),r.getString(2)));}
            }
            return allows(request.toolName(),call,observations);
        }
    }

    static boolean allows(String exposed, JsonNode call, List<Observation> observations) {
        if(call==null||call.isArray())return false; // Never approve an ambiguous batch.
        var fn=call.path("function");
        if(!exposed.equals(fn.path("name").asText()))return false;
        JsonNode args;
        try{args=JSON.readTree(fn.path("arguments").asText());}catch(Exception e){return false;}
        if(args==null||!args.isObject())return false;
        if(exposed.equals("run_command")) {
            if(!keys(args,Set.of("command"))||!args.path("command").isTextual())return false;
            // Exact GET commands, not shell-prefix approval; no redirects or extra flags.
            for(String path:List.of("/","/api/invoices?filter=INV-2047","/api/risk-check?invoice=INV-2047"))
                for(String flag:List.of("-s", "-s -i"))
                    if(args.get("command").asText().equals("curl "+flag+" \""+URL+path+"\""))return true;
            return false;
        }
        String tool=original(exposed);
        if(tool==null)return false;
        if(tool.equals("new_page"))return keys(args,Set.of("url"))&&args.path("url").isTextual()
                &&Set.of(URL,URL+"/").contains(args.get("url").asText());
        // Resolve the owned page only from an actual successful new_page result.
        Integer page=null;String snapshot=null;
        var pagePattern=Pattern.compile("(?m)^(\\d+): D7 invoice filter \\("+Pattern.quote(URL)+"/\\) \\[selected\\]$");
        for(var o:observations) {
            if("new_page".equals(original(o.tool()))) {
                var m=pagePattern.matcher(o.output());page=m.find()?Integer.valueOf(m.group(1)):null;
                snapshot=null;
            } else if(page!=null&&"take_snapshot".equals(original(o.tool()))
                    &&o.output().contains("RootWebArea \"D7 invoice filter\" url=\""+URL+"/\""))snapshot=o.output();
        }
        if(page==null||!args.path("pageId").isIntegralNumber()||!args.path("pageId").canConvertToInt()
                ||args.get("pageId").intValue()!=page)return false;
        return switch(tool) {
            case "take_snapshot" -> keys(args,Set.of("pageId","verbose"))&&optionalBoolean(args,"verbose");
            case "list_console_messages" -> keys(args,Set.of("pageId","includeStackTraces"))&&optionalBoolean(args,"includeStackTraces");
            case "list_network_requests" -> keys(args,Set.of("pageId"));
            case "fill" -> keys(args,Set.of("pageId","uid","value"))&&args.path("value").isTextual()
                    &&args.get("value").asText().equals("INV-2047")&&element(snapshot,args,"textbox","Filter");
            case "click" -> keys(args,Set.of("pageId","uid"))&&element(snapshot,args,"button","Apply");
            default -> false;
        };
    }
    private static boolean element(String snapshot,JsonNode args,String role,String label) {
        if(snapshot==null||!args.path("uid").isTextual())return false;
        return Pattern.compile("(?m)^\\s*uid="+Pattern.quote(args.get("uid").asText())+" "+role+" \""+label+"\"(?: .*)?$")
                .matcher(snapshot).find();
    }
    private static boolean keys(JsonNode args,Set<String> allowed) {
        var names=args.fieldNames();while(names.hasNext())if(!allowed.contains(names.next()))return false;return true;
    }
    private static boolean optionalBoolean(JsonNode args,String key){return !args.has(key)||args.get(key).isBoolean();}
    private static String original(String exposed) {
        var m=Pattern.compile("^mcp_chrome_(new_page|take_snapshot|fill|click|list_console_messages|list_network_requests)_[a-f0-9]{24}$").matcher(exposed);
        return m.matches()?m.group(1):null;
    }
}
