package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.model.ToolCall;
import com.paicli.skill.*;
import com.paicli.state.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, fixed original packages and real Python Playwright; no paid provider. */
@EnabledIfSystemProperty(named="paicli.skills.sources",matches=".+")
class SkillEcosystemOriginalIT {
    @TempDir Path workspace;

    @Test void originalPackagesAndUnmodifiedHelperRunThroughOfficialTask()throws Exception {
        var json=new ObjectMapper();
        Path sources=Path.of(System.getProperty("paicli.skills.sources"));
        Path python=Path.of(System.getProperty("paicli.skills.python"));
        Path chrome=Path.of(System.getProperty("paicli.skills.chrome"));
        Path evidence=Path.of(System.getProperty("paicli.skills.evidence"));
        Files.createDirectories(evidence);
        assertTrue(Files.isRegularFile(python));assertTrue(Files.isRegularFile(chrome));
        var originals=List.of(sources.resolve("vercel-labs-agent-skills/skills/react-best-practices"),
                sources.resolve("anthropics-skills/skills/webapp-testing"),sources.resolve("obra-superpowers/skills/systematic-debugging"));
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Files.writeString(workspace.resolve("server.py"),"""
                import os
                from pathlib import Path
                from http.server import BaseHTTPRequestHandler, HTTPServer
                class Handler(BaseHTTPRequestHandler):
                    def do_GET(self):
                        api=self.path.startswith('/api')
                        self.send_response(503 if api else 200)
                        self.send_header('Content-Type','text/html; charset=utf-8')
                        self.end_headers()
                        self.wfile.write(b'fixture unavailable' if api else b'''<h1>Skill fixture</h1><input id="query"><button id="check" onclick="fetch('/api?query='+document.querySelector('#query').value).then(r=>{document.querySelector('#result').textContent='status='+r.status;console.log('fixture checked')})">Check</button><p id="result"></p>''')
                Path('server.pid').write_text(str(os.getpid()))
                HTTPServer(('127.0.0.1',PORT),Handler).serve_forever()
                """.replace("PORT",Integer.toString(port)));
        Files.writeString(workspace.resolve("automation.py"),"""
                import json
                from pathlib import Path
                from playwright.sync_api import sync_playwright, expect
                with sync_playwright() as p:
                    browser=p.chromium.launch(executable_path=CHROME,headless=True)
                    try:
                        page=browser.new_page()
                        messages=[]
                        responses=[]
                        page.on('console',lambda msg: messages.append(msg.text))
                        page.on('response',lambda response: responses.append({'url':response.url,'status':response.status}))
                        page.goto('http://127.0.0.1:PORT',wait_until='networkidle')
                        expect(page.locator('h1')).to_have_text('Skill fixture')
                        page.locator('#query').fill('alpha')
                        page.locator('#check').click()
                        expect(page.locator('#result')).to_have_text('status=503')
                        assert 'fixture checked' in messages
                        assert any('/api?query=alpha' in r['url'] and r['status']==503 for r in responses)
                        Path('playwright-result.json').write_text(json.dumps({'browser':browser.version,'console':messages,'responses':responses,'result':page.locator('#result').inner_text(),'marker':'REAL_PYTHON_PLAYWRIGHT_PASS'},indent=2))
                        print('REAL_PYTHON_PLAYWRIGHT_PASS',flush=True)
                    finally:
                        browser.close()
                """.replace("CHROME",json.writeValueAsString(chrome.toString())).replace("PORT",Integer.toString(port)));
        var calls=new ArrayList<ToolCall>();
        String[] resources={"rules/async-dependencies.md","scripts/with_server.py","root-cause-tracing.md"};
        for(int i=0;i<originals.size();i++){
            String name=SkillLoader.inspectPackage(originals.get(i)).name();
            calls.add(new ToolCall("load_skill",Map.of("name",name)));
            calls.add(new ToolCall("read_skill_resource",Map.of("name",name,"path",resources[i],"max_chars",4096)));
        }
        String py=python.toString().replace('\\','/');
        assertFalse(py.contains(" "),"This fixed Windows fixture uses a dependency path without spaces");
        String helper=py+" .paicli/skills/webapp-testing/scripts/with_server.py";
        calls.add(new ToolCall("run_command",Map.of("command",helper+" --help")));
        calls.add(new ToolCall("run_command",Map.of("command",helper+" --server \""+py+" server.py\" --port "+port+" -- "+py+" automation.py")));
        AtomicReference<ProcessHandle> server=new AtomicReference<>();AtomicBoolean stop=new AtomicBoolean();
        Thread observer=new Thread(()->{
            while(!stop.get()){
                try{
                    Path pid=workspace.resolve("server.pid");
                    if(Files.exists(pid))ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).ifPresent(server::set);
                    if(server.get()!=null)return;
                    Thread.sleep(10);
                }catch(Exception ignored){ }
            }
        },"skill-fixture-owned-server-observer");
        observer.setDaemon(true);observer.start();
        var record=new LinkedHashMap<String,Object>();
        try {
            var result=SkillEcosystemProductionIT.execute(workspace,originals,"Use relevant Skills to inspect their reference material and test the local dedicated page",calls,"original");
            record.put("task",result);record.put("paidProviderCalls",0);
            assertEquals(2,result.approvals());
            assertTrue(result.observed().stream().anyMatch(s->s.contains("REAL_PYTHON_PLAYWRIGHT_PASS")),result.toString());
            assertTrue(Files.exists(workspace.resolve("playwright-result.json")));
            assertNotNull(server.get(),"server identity was observed during task");
            record.put("serverPid",server.get().pid());record.put("serverAliveAfterTask",server.get().isAlive());
            assertFalse(server.get().isAlive(),"Original helper's child server must be cleaned by host even after its shell exits");
            try(var state=new StateStore(workspace)){
                var loads=state.skillLoads.findByStatus(SkillLoadStore.STATUS_LOADED).stream().filter(l->l.taskId().equals(result.taskId())).toList();
                assertEquals(3,loads.size());String run=loads.getFirst().runId();
                record.put("loads",loads);record.put("accesses",state.skillLoads.accessesForRun(run));
                record.put("report",state.skillLoads.reportForTaskRun(result.taskId(),run));
                assertEquals(6,state.skillLoads.accessesForRun(run).stream().filter(a->a.kind().equals("COMMAND")&&a.status().equals("SUCCEEDED")).count());
                for(Path original:originals)assertEquals(SkillPackageFiles.snapshot(original).hash(),SkillPackageFiles.snapshot(workspace.resolve(".paicli/skills").resolve(SkillLoader.inspectPackage(original).name())).hash());
            }
            record.put("status","PASS");
        }finally {
            stop.set(true);observer.join(1000);
            if(server.get()!=null&&server.get().isAlive()){record.put("fixtureFallbackCleanup",true);server.get().destroyForcibly();server.get().onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);}
            for(String file:List.of("server.py","automation.py","playwright-result.json"))if(Files.exists(workspace.resolve(file)))Files.copy(workspace.resolve(file),evidence.resolve(file),StandardCopyOption.REPLACE_EXISTING);
            json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("result.json").toFile(),record);
        }
    }
}
