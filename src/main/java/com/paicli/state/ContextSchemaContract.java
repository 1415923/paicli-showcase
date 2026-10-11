package com.paicli.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical V26 Context Generation schema shared by migration and attestation. */
final class ContextSchemaContract {
    static final String CONTRACT_VERSION = "V26_CONTEXT_GENERATION_CONTRACT_V1";
    static final boolean EXPECTED_STRICT = true;
    private static final Map<String,String> TABLES=tables();
    private static final Map<String,String> INDEXES=indexes();
    private static final Map<String,String> TRIGGERS=triggers();
    private static final Map<String,Set<String>> EXPECTED_COLUMNS=expectedColumnMap();
    private ContextSchemaContract(){}
    static List<String> statements(){var out=new ArrayList<String>();out.addAll(TABLES.values());out.addAll(INDEXES.values());out.addAll(TRIGGERS.values());return List.copyOf(out);}
    static Set<String> tableNames(){return TABLES.keySet();}
    static Set<String> indexNames(){return INDEXES.keySet();}
    static Set<String> triggerNames(){return TRIGGERS.keySet();}
    static Map<String,Set<String>> expectedColumns(){return EXPECTED_COLUMNS;}
    static Map<String,String> tableFingerprints(){return fingerprints(TABLES);}
    static Map<String,String> indexFingerprints(){return fingerprints(INDEXES);}
    static Map<String,String> triggerFingerprints(){return fingerprints(TRIGGERS);}
    private static Map<String,String> fingerprints(Map<String,String> values){var out=new LinkedHashMap<String,String>();values.forEach((n,s)->out.put(n,ProviderTelemetrySchemaContract.fingerprint(s)));return java.util.Collections.unmodifiableMap(out);}

    private static Map<String,String> tables(){
        var m=new LinkedHashMap<String,String>();
        m.put("context_generations","""
          CREATE TABLE context_generations (
            context_generation_id TEXT PRIMARY KEY CHECK(length(context_generation_id)=36 AND substr(context_generation_id,1,4)='ctx_' AND substr(context_generation_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(context_generation_id,17,1)='4' AND substr(context_generation_id,21,1) IN ('8','9','a','b')),
            workspace_id TEXT NOT NULL CHECK(length(workspace_id) BETWEEN 1 AND 256),
            thread_id TEXT NOT NULL, task_id TEXT NOT NULL, run_id TEXT NOT NULL,
            source_authority_generation_id TEXT NOT NULL,
            generation_kind TEXT NOT NULL CHECK(generation_kind IN ('BASE','COMPACTED')),
            operation_kind TEXT NOT NULL CHECK(operation_kind IN ('PLAN','CHAT','TOOL_CONTINUATION','VERIFICATION','REPAIR')),
            provider TEXT NOT NULL CHECK(length(provider) BETWEEN 1 AND 64),
            model TEXT NOT NULL CHECK(length(model) BETWEEN 1 AND 128),
            capability_profile TEXT NOT NULL CHECK(length(capability_profile) BETWEEN 1 AND 128),
            compiler_version TEXT NOT NULL CHECK(length(compiler_version) BETWEEN 1 AND 64),
            policy_version TEXT NOT NULL CHECK(length(policy_version) BETWEEN 1 AND 64),
            manifest_hash TEXT NOT NULL CHECK(length(manifest_hash)=64 AND manifest_hash NOT GLOB '*[^0-9a-f]*'),
            plan_revision TEXT, steering_revision TEXT,
            source_fingerprint TEXT NOT NULL CHECK(length(source_fingerprint)=64 AND source_fingerprint NOT GLOB '*[^0-9a-f]*'),
            compiled_hash TEXT CHECK(compiled_hash IS NULL OR (length(compiled_hash)=64 AND compiled_hash NOT GLOB '*[^0-9a-f]*')),
            input_estimate INTEGER CHECK(input_estimate IS NULL OR input_estimate>=0),
            tool_schema_estimate INTEGER CHECK(tool_schema_estimate IS NULL OR tool_schema_estimate>=0),
            output_reserve INTEGER CHECK(output_reserve IS NULL OR output_reserve>=0),
            safety_margin INTEGER CHECK(safety_margin IS NULL OR safety_margin>=0),
            context_limit INTEGER CHECK(context_limit IS NULL OR context_limit>0),
            limit_provenance TEXT NOT NULL CHECK(limit_provenance IN ('PROVIDER_REPORTED_LIMIT','CONFIGURED_LIMIT','MODEL_CATALOG_LIMIT','UNKNOWN_LIMIT')),
            estimate_provenance TEXT NOT NULL CHECK(estimate_provenance IN ('TOKENIZER','DERIVED_ESTIMATE')),
            source_context_generation_id TEXT, source_pointer_version INTEGER CHECK(source_pointer_version IS NULL OR source_pointer_version>=0),
            lifecycle TEXT NOT NULL CHECK(lifecycle IN ('PREPARING','PREPARED','READY','FAILED','SUPERSEDED')),
            state_version INTEGER NOT NULL DEFAULT 0 CHECK(state_version>=0),
            failure_code TEXT CHECK(failure_code IS NULL OR length(failure_code) BETWEEN 1 AND 64),
            preview_summary TEXT CHECK(preview_summary IS NULL OR length(preview_summary)<=2048),
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
            UNIQUE(task_id,run_id,operation_kind,context_generation_id),
            FOREIGN KEY(thread_id) REFERENCES work_threads(thread_id),
            FOREIGN KEY(task_id) REFERENCES task_identities(task_id),
            FOREIGN KEY(run_id) REFERENCES durable_runs(run_id),
            FOREIGN KEY(thread_id,task_id,run_id) REFERENCES durable_runs(thread_id,task_id,run_id),
            FOREIGN KEY(source_authority_generation_id) REFERENCES task_context_generations(generation_id),
            FOREIGN KEY(task_id,source_authority_generation_id) REFERENCES task_context_generations(task_id,generation_id),
            FOREIGN KEY(source_context_generation_id) REFERENCES context_generations(context_generation_id),
            CHECK((generation_kind='BASE' AND source_context_generation_id IS NULL) OR (generation_kind='COMPACTED' AND source_context_generation_id IS NOT NULL)),
            CHECK((lifecycle='PREPARING' AND compiled_hash IS NULL) OR lifecycle='FAILED' OR (lifecycle IN ('PREPARED','READY','SUPERSEDED') AND compiled_hash IS NOT NULL)),
            CHECK(generation_kind='COMPACTED' OR lifecycle<>'PREPARED'),
            CHECK((lifecycle='FAILED' AND failure_code IS NOT NULL) OR (lifecycle<>'FAILED' AND failure_code IS NULL)),
            CHECK(updated_at>=created_at)
          ) STRICT
          """);
        m.put("context_segments","""
          CREATE TABLE context_segments (
            context_generation_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0),
            source_class TEXT NOT NULL CHECK(source_class IN ('NON_COMPACTABLE_AUTHORITY','COMPACTABLE_DURABLE_HISTORY','FRESH_USER_INTENT','CHILD_CONTEXT')),
            segment_kind TEXT NOT NULL CHECK(length(segment_kind) BETWEEN 1 AND 64),
            scope TEXT NOT NULL CHECK(scope IN ('RUN_SCOPED','TASK_SCOPED','CHILD_SCOPED')),
            compactability TEXT NOT NULL CHECK(compactability IN ('COMPACTABLE','NON_COMPACTABLE')),
            payload_mode TEXT NOT NULL CHECK(payload_mode IN ('REFERENCE_RENDERED','INLINE_SANITIZED')),
            sanitized_payload TEXT,
            content_hash TEXT NOT NULL CHECK(length(content_hash)=64 AND content_hash NOT GLOB '*[^0-9a-f]*'),
            estimated_tokens INTEGER NOT NULL CHECK(estimated_tokens>=0),
            privacy_class TEXT NOT NULL CHECK(privacy_class IN ('PUBLIC','INTERNAL','SENSITIVE_REDACTED')),
            bounds INTEGER NOT NULL CHECK(bounds BETWEEN 0 AND 1048576),
            PRIMARY KEY(context_generation_id,ordinal),
            FOREIGN KEY(context_generation_id) REFERENCES context_generations(context_generation_id),
            CHECK((payload_mode='REFERENCE_RENDERED' AND sanitized_payload IS NULL) OR (payload_mode='INLINE_SANITIZED' AND sanitized_payload IS NOT NULL AND length(sanitized_payload)<=bounds))
          ) STRICT
          """);
        m.put("context_source_references","""
          CREATE TABLE context_source_references (
            context_generation_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0),
            source_type TEXT NOT NULL CHECK(length(source_type) BETWEEN 1 AND 64),
            source_id TEXT NOT NULL CHECK(length(source_id) BETWEEN 1 AND 256), source_revision TEXT,
            source_hash TEXT NOT NULL CHECK(length(source_hash)=64 AND source_hash NOT GLOB '*[^0-9a-f]*'),
            source_order INTEGER NOT NULL CHECK(source_order>=0),
            PRIMARY KEY(context_generation_id,ordinal,source_order),
            FOREIGN KEY(context_generation_id,ordinal) REFERENCES context_segments(context_generation_id,ordinal)
          ) STRICT
          """);
        m.put("context_generation_pointers","""
          CREATE TABLE context_generation_pointers (
            task_id TEXT NOT NULL, run_id TEXT NOT NULL,
            operation_kind TEXT NOT NULL CHECK(operation_kind IN ('PLAN','CHAT','TOOL_CONTINUATION','VERIFICATION','REPAIR')),
            active_context_generation_id TEXT NOT NULL, pointer_version INTEGER NOT NULL CHECK(pointer_version>=1),
            updated_at TEXT NOT NULL, PRIMARY KEY(task_id,run_id,operation_kind),
            FOREIGN KEY(task_id) REFERENCES task_identities(task_id), FOREIGN KEY(run_id) REFERENCES durable_runs(run_id),
            FOREIGN KEY(active_context_generation_id) REFERENCES context_generations(context_generation_id)
            ,FOREIGN KEY(task_id,run_id,operation_kind,active_context_generation_id)
              REFERENCES context_generations(task_id,run_id,operation_kind,context_generation_id)
          ) STRICT
          """);
        m.put("provider_context_telemetry_links","""
          CREATE TABLE provider_context_telemetry_links (
            telemetry_id INTEGER NOT NULL UNIQUE, context_generation_id TEXT NOT NULL, linked_at TEXT NOT NULL,
            PRIMARY KEY(telemetry_id,context_generation_id),
            FOREIGN KEY(telemetry_id) REFERENCES provider_call_telemetry(telemetry_id) ON DELETE CASCADE,
            FOREIGN KEY(context_generation_id) REFERENCES context_generations(context_generation_id)
          ) STRICT
          """);
        return java.util.Collections.unmodifiableMap(m);
    }
    private static Map<String,Set<String>> expectedColumnMap(){
        return Map.of(
          "context_generations",Set.of("context_generation_id","workspace_id","thread_id","task_id","run_id",
            "source_authority_generation_id","generation_kind","operation_kind","provider","model",
            "capability_profile","compiler_version","policy_version","manifest_hash","plan_revision",
            "steering_revision","source_fingerprint","compiled_hash","input_estimate","tool_schema_estimate",
            "output_reserve","safety_margin","context_limit","limit_provenance","estimate_provenance",
            "source_context_generation_id","source_pointer_version","lifecycle","state_version","failure_code",
            "preview_summary","created_at","updated_at"),
          "context_segments",Set.of("context_generation_id","ordinal","source_class","segment_kind","scope",
            "compactability","payload_mode","sanitized_payload","content_hash","estimated_tokens",
            "privacy_class","bounds"),
          "context_source_references",Set.of("context_generation_id","ordinal","source_type","source_id",
            "source_revision","source_hash","source_order"),
          "context_generation_pointers",Set.of("task_id","run_id","operation_kind",
            "active_context_generation_id","pointer_version","updated_at"),
          "provider_context_telemetry_links",Set.of("telemetry_id","context_generation_id","linked_at"));
    }
    private static Map<String,String> indexes(){var m=new LinkedHashMap<String,String>();
        m.put("idx_v26_context_identity","CREATE INDEX idx_v26_context_identity ON context_generations(task_id,run_id,operation_kind,created_at)");
        m.put("idx_v26_context_source","CREATE INDEX idx_v26_context_source ON context_generations(source_context_generation_id,lifecycle)");
        m.put("idx_v26_context_active_candidate","CREATE UNIQUE INDEX idx_v26_context_active_candidate ON context_generations(source_context_generation_id,source_fingerprint,policy_version,compiler_version) WHERE generation_kind='COMPACTED' AND lifecycle IN ('PREPARING','PREPARED')");
        m.put("idx_v26_context_ready_replacement","CREATE UNIQUE INDEX idx_v26_context_ready_replacement ON context_generations(source_context_generation_id) WHERE generation_kind='COMPACTED' AND lifecycle IN ('READY','SUPERSEDED')");
        m.put("idx_v26_context_segments_class","CREATE INDEX idx_v26_context_segments_class ON context_segments(context_generation_id,source_class,ordinal)");
        m.put("idx_v26_context_source_ref","CREATE INDEX idx_v26_context_source_ref ON context_source_references(source_type,source_id)");
        m.put("idx_v26_context_telemetry_ctx","CREATE INDEX idx_v26_context_telemetry_ctx ON provider_context_telemetry_links(context_generation_id)");
        return java.util.Collections.unmodifiableMap(m);}
    private static Map<String,String> triggers(){var m=new LinkedHashMap<String,String>();
        m.put("v26_context_state_transition","""
          CREATE TRIGGER v26_context_state_transition BEFORE UPDATE OF lifecycle ON context_generations
          WHEN NOT (OLD.lifecycle=NEW.lifecycle
            OR (OLD.generation_kind='BASE' AND OLD.lifecycle='PREPARING' AND NEW.lifecycle IN ('READY','FAILED'))
            OR (OLD.generation_kind='COMPACTED' AND OLD.lifecycle='PREPARING' AND NEW.lifecycle IN ('PREPARED','FAILED'))
            OR (OLD.generation_kind='COMPACTED' AND OLD.lifecycle='PREPARED' AND NEW.lifecycle IN ('READY','FAILED'))
            OR (OLD.lifecycle='READY' AND NEW.lifecycle='SUPERSEDED'))
          BEGIN SELECT RAISE(ABORT,'invalid context transition'); END
          """);
        m.put("v26_context_frozen_immutable","""
          CREATE TRIGGER v26_context_frozen_immutable BEFORE UPDATE ON context_generations
          WHEN OLD.lifecycle IN ('PREPARED','READY','SUPERSEDED') AND (
            (OLD.lifecycle='PREPARED' AND (NEW.lifecycle NOT IN ('READY','FAILED')
              OR NEW.state_version<>OLD.state_version+1))
            OR (OLD.lifecycle='READY' AND (NEW.lifecycle<>'SUPERSEDED'
              OR NEW.state_version<>OLD.state_version+1))
            OR OLD.lifecycle='SUPERSEDED'
            OR OLD.context_generation_id IS NOT NEW.context_generation_id
            OR OLD.workspace_id IS NOT NEW.workspace_id OR OLD.thread_id IS NOT NEW.thread_id
            OR OLD.task_id IS NOT NEW.task_id OR OLD.run_id IS NOT NEW.run_id
            OR OLD.source_authority_generation_id IS NOT NEW.source_authority_generation_id
            OR OLD.generation_kind IS NOT NEW.generation_kind OR OLD.operation_kind IS NOT NEW.operation_kind
            OR OLD.provider IS NOT NEW.provider OR OLD.model IS NOT NEW.model
            OR OLD.capability_profile IS NOT NEW.capability_profile OR OLD.compiler_version IS NOT NEW.compiler_version
            OR OLD.policy_version IS NOT NEW.policy_version OR OLD.manifest_hash IS NOT NEW.manifest_hash
            OR OLD.plan_revision IS NOT NEW.plan_revision OR OLD.steering_revision IS NOT NEW.steering_revision
            OR OLD.source_fingerprint IS NOT NEW.source_fingerprint OR OLD.compiled_hash IS NOT NEW.compiled_hash
            OR OLD.input_estimate IS NOT NEW.input_estimate OR OLD.tool_schema_estimate IS NOT NEW.tool_schema_estimate
            OR OLD.output_reserve IS NOT NEW.output_reserve OR OLD.safety_margin IS NOT NEW.safety_margin
            OR OLD.context_limit IS NOT NEW.context_limit OR OLD.limit_provenance IS NOT NEW.limit_provenance
            OR OLD.estimate_provenance IS NOT NEW.estimate_provenance
            OR OLD.source_context_generation_id IS NOT NEW.source_context_generation_id
            OR OLD.source_pointer_version IS NOT NEW.source_pointer_version
            OR OLD.preview_summary IS NOT NEW.preview_summary
            OR (NEW.lifecycle<>'FAILED' AND OLD.failure_code IS NOT NEW.failure_code)
            OR OLD.created_at IS NOT NEW.created_at OR NEW.updated_at<OLD.updated_at)
          BEGIN SELECT RAISE(ABORT,'prepared and ready context content is immutable'); END
          """);
        m.put("v26_context_source_scope","""
          CREATE TRIGGER v26_context_source_scope BEFORE INSERT ON context_generations
          WHEN NEW.source_context_generation_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM context_generations source
            WHERE source.context_generation_id=NEW.source_context_generation_id
              AND source.workspace_id=NEW.workspace_id AND source.thread_id=NEW.thread_id
              AND source.task_id=NEW.task_id AND source.run_id=NEW.run_id
              AND source.operation_kind=NEW.operation_kind AND source.lifecycle='READY')
          BEGIN SELECT RAISE(ABORT,'context source scope mismatch'); END
          """);
        m.put("v26_context_pointer_ready_insert","""
          CREATE TRIGGER v26_context_pointer_ready_insert BEFORE INSERT ON context_generation_pointers
          WHEN NOT EXISTS (SELECT 1 FROM context_generations g
            WHERE g.context_generation_id=NEW.active_context_generation_id AND g.lifecycle='READY')
          BEGIN SELECT RAISE(ABORT,'context pointer requires READY'); END
          """);
        m.put("v26_context_pointer_ready_update","""
          CREATE TRIGGER v26_context_pointer_ready_update BEFORE UPDATE OF active_context_generation_id ON context_generation_pointers
          WHEN NOT EXISTS (SELECT 1 FROM context_generations g
            WHERE g.context_generation_id=NEW.active_context_generation_id AND g.lifecycle='READY')
          BEGIN SELECT RAISE(ABORT,'context pointer requires READY'); END
          """);
        m.put("v26_context_no_supersede_active","""
          CREATE TRIGGER v26_context_no_supersede_active BEFORE UPDATE OF lifecycle ON context_generations
          WHEN OLD.lifecycle='READY' AND NEW.lifecycle='SUPERSEDED' AND EXISTS (
            SELECT 1 FROM context_generation_pointers p
            WHERE p.active_context_generation_id=OLD.context_generation_id)
          BEGIN SELECT RAISE(ABORT,'active context cannot be superseded'); END
          """);
        m.put("v26_context_segment_update_immutable","""
          CREATE TRIGGER v26_context_segment_update_immutable BEFORE UPDATE ON context_segments
          WHEN (SELECT lifecycle FROM context_generations WHERE context_generation_id=OLD.context_generation_id) IN ('PREPARED','READY','SUPERSEDED')
          BEGIN SELECT RAISE(ABORT,'prepared context segments are immutable'); END
          """);
        m.put("v26_context_segment_delete_immutable","""
          CREATE TRIGGER v26_context_segment_delete_immutable BEFORE DELETE ON context_segments
          WHEN (SELECT lifecycle FROM context_generations WHERE context_generation_id=OLD.context_generation_id) IN ('PREPARED','READY','SUPERSEDED')
          BEGIN SELECT RAISE(ABORT,'prepared context segments are immutable'); END
          """);
        m.put("v26_context_segment_insert_immutable","""
          CREATE TRIGGER v26_context_segment_insert_immutable BEFORE INSERT ON context_segments
          WHEN (SELECT lifecycle FROM context_generations WHERE context_generation_id=NEW.context_generation_id) IN ('PREPARED','READY','SUPERSEDED')
          BEGIN SELECT RAISE(ABORT,'prepared context segments are immutable'); END
          """);
        m.put("v26_context_reference_update_immutable","""
          CREATE TRIGGER v26_context_reference_update_immutable BEFORE UPDATE ON context_source_references
          WHEN (SELECT lifecycle FROM context_generations WHERE context_generation_id=OLD.context_generation_id) IN ('PREPARED','READY','SUPERSEDED')
          BEGIN SELECT RAISE(ABORT,'prepared context references are immutable'); END
          """);
        m.put("v26_context_reference_delete_immutable","""
          CREATE TRIGGER v26_context_reference_delete_immutable BEFORE DELETE ON context_source_references
          WHEN (SELECT lifecycle FROM context_generations WHERE context_generation_id=OLD.context_generation_id) IN ('PREPARED','READY','SUPERSEDED')
          BEGIN SELECT RAISE(ABORT,'prepared context references are immutable'); END
          """);
        m.put("v26_context_reference_insert_immutable","""
          CREATE TRIGGER v26_context_reference_insert_immutable BEFORE INSERT ON context_source_references
          WHEN (SELECT lifecycle FROM context_generations WHERE context_generation_id=NEW.context_generation_id) IN ('PREPARED','READY','SUPERSEDED')
          BEGIN SELECT RAISE(ABORT,'prepared context references are immutable'); END
          """);
        return java.util.Collections.unmodifiableMap(m);}
}
