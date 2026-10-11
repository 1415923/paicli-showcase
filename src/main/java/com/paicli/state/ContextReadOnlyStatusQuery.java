package com.paicli.state;

import com.paicli.context.ContextModels.Budget;
import com.paicli.context.ContextModels.Lifecycle;
import com.paicli.context.ContextModels.OperationKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Structural read-only Context status: complete tuples, no migration, claim or cleanup. */
public final class ContextReadOnlyStatusQuery {
    private final Path database;
    public ContextReadOnlyStatusQuery(Path workspace){
        this.database=workspace.toAbsolutePath().normalize().resolve(".paicli/state/paicli.db");
    }

    public Status read(String taskFilter,String runFilter,String currentTask,String currentRun){
        if(!Files.isRegularFile(database))return Status.unavailable();
        String url="jdbc:sqlite:file:"+database.toString().replace('\\','/')+"?mode=ro";
        try(var connection=DriverManager.getConnection(url)){
            try(var pragma=connection.createStatement()){pragma.execute("PRAGMA query_only=ON");}
            if(!compatible(connection))return Status.unavailable();
            StringBuilder sql=new StringBuilder("""
              SELECT w.workspace_identity,g.thread_id,p.task_id,p.run_id,p.operation_kind,
                     p.active_context_generation_id,p.pointer_version,g.lifecycle,
                     g.input_estimate,g.tool_schema_estimate,g.output_reserve,g.safety_margin,
                     g.context_limit,
                     (SELECT count(*) FROM context_segments x
                        WHERE x.context_generation_id=g.context_generation_id
                          AND x.compactability='COMPACTABLE'),
                     (SELECT c.context_generation_id FROM context_generations c
                        WHERE c.source_context_generation_id=g.context_generation_id
                          AND c.lifecycle IN ('PREPARING','PREPARED')
                        ORDER BY CASE c.lifecycle WHEN 'PREPARED' THEN 0 ELSE 1 END,
                                 c.context_generation_id LIMIT 1),
                     (SELECT c.lifecycle FROM context_generations c
                        WHERE c.source_context_generation_id=g.context_generation_id
                          AND c.lifecycle IN ('PREPARING','PREPARED')
                        ORDER BY CASE c.lifecycle WHEN 'PREPARED' THEN 0 ELSE 1 END,
                                 c.context_generation_id LIMIT 1),
                     p.updated_at
              FROM context_generation_pointers p
              LEFT JOIN context_generations g ON g.context_generation_id=p.active_context_generation_id
              LEFT JOIN task_identities t ON t.task_id=p.task_id
              LEFT JOIN work_threads w ON w.thread_id=t.thread_id
              WHERE 1=1
              """);
            if(taskFilter!=null)sql.append(" AND p.task_id=?");
            if(runFilter!=null)sql.append(" AND p.run_id=?");
            sql.append(" ORDER BY g.thread_id ASC,p.task_id ASC,p.run_id ASC,p.operation_kind ASC");
            var rows=new ArrayList<CompactTargetStatusRow>();
            try(var statement=connection.prepareStatement(sql.toString())){
                int i=1;if(taskFilter!=null)statement.setString(i++,taskFilter);
                if(runFilter!=null)statement.setString(i,runFilter);
                try(var row=statement.executeQuery()){while(row.next())rows.add(toRow(row,currentTask,currentRun));}
            }
            return new Status(true,List.copyOf(rows));
        }catch(Exception unavailable){return Status.unavailable();}
    }

    public Status read(String taskId){return read(taskId,null,null,null);}

    private static CompactTargetStatusRow toRow(java.sql.ResultSet row,String currentTask,
            String currentRun)throws Exception{
        String workspace=row.getString(1),thread=row.getString(2),task=row.getString(3),run=row.getString(4);
        String operation=row.getString(5),context=row.getString(6),lifecycle=row.getString(8);
        RowOutcome outcome=workspace==null||thread==null||task==null||run==null||operation==null
                ||context==null||lifecycle==null?RowOutcome.INTEGRITY_FAILURE:RowOutcome.PRESENT;
        OperationKind parsedOperation=null;Lifecycle parsedLifecycle=null;
        try{if(operation!=null)parsedOperation=OperationKind.valueOf(operation);
            if(lifecycle!=null)parsedLifecycle=Lifecycle.valueOf(lifecycle);}
        catch(RuntimeException invalid){outcome=RowOutcome.INTEGRITY_FAILURE;}
        Budget.Decision budget=Budget.Decision.CONTEXT_BUDGET_UNKNOWN;
        Object limit=row.getObject(13);long compactable=row.getLong(14);
        if(limit!=null)try{
            long total=Math.addExact(Math.addExact(row.getLong(9),row.getLong(10)),
                    Math.addExact(row.getLong(11),row.getLong(12)));
            budget=total<=((Number)limit).longValue()?Budget.Decision.READY:
                    compactable>0?Budget.Decision.COMPACTION_REQUIRED:
                            Budget.Decision.CONTEXT_BUDGET_EXCEEDED;
        }catch(ArithmeticException overflow){outcome=RowOutcome.INTEGRITY_FAILURE;}
        String relation=Objects.equals(task,currentTask)&&Objects.equals(run,currentRun)
                ?"CURRENT_SESSION_RUN":"HISTORICAL_RUN";
        return new CompactTargetStatusRow(workspace,thread,task,run,parsedOperation,context,row.getLong(7),
                parsedLifecycle,budget,compactable>0,row.getString(15),row.getString(16),relation,
                row.getString(17),outcome);
    }

    private static boolean compatible(java.sql.Connection connection)throws Exception{
        try(var statement=connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version=26");
                var row=statement.executeQuery()){return row.next();}
    }
    private static String shortId(String id){return id==null?"none":id.substring(0,Math.min(16,id.length()));}

    public enum RowOutcome { PRESENT, INTEGRITY_FAILURE }
    public record CompactTargetStatusRow(String workspaceId,String threadId,String taskId,String runId,
            OperationKind operation,String activeContextGenerationId,long pointerVersion,
            Lifecycle lifecycle,Budget.Decision budgetDecision,boolean compactable,
            String preparedCandidateId,String candidateLifecycle,String currentSessionRelation,
            String updatedAt,RowOutcome outcome){ }
    public record Status(boolean available,List<CompactTargetStatusRow> rows){
        public Status{rows=List.copyOf(rows);}
        static Status unavailable(){return new Status(false,List.of());}
        public List<String> lines(){
            if(!available)return List.of("CONTEXT_SCHEMA_UNAVAILABLE");
            if(rows.isEmpty())return List.of("No Context Generation pointers.");
            return rows.stream().map(row->row.outcome()+" thread="+shortId(row.threadId())
                    +" task="+shortId(row.taskId())+" run="+shortId(row.runId())
                    +" operation="+Objects.toString(row.operation(),"unknown")
                    +" ctx="+shortId(row.activeContextGenerationId())+" v"+row.pointerVersion()
                    +" lifecycle="+Objects.toString(row.lifecycle(),"unknown")
                    +" budget="+row.budgetDecision()+" compactable="+row.compactable()
                    +" candidate="+shortId(row.preparedCandidateId())
                    +" relation="+row.currentSessionRelation()).toList();
        }
    }
}
