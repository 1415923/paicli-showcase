package com.paicli.state;

import java.util.List;

/** Additive Skill package and access provenance. Existing discovery/selection rows are retained. */
final class V33Migration {
    static List<String> statements() {
        return List.of("""
                CREATE TABLE skill_package_bindings (
                    skill_load_id TEXT PRIMARY KEY REFERENCES skill_loads(skill_load_id),
                    package_sha256 TEXT NOT NULL,
                    manifest_json TEXT NOT NULL
                )
                """, """
                CREATE TABLE skill_accesses (
                    access_id TEXT PRIMARY KEY,
                    skill_load_id TEXT NOT NULL REFERENCES skill_loads(skill_load_id),
                    kind TEXT NOT NULL CHECK(kind IN ('ACTIVATE','READ','COMMAND')),
                    tool_call_id TEXT, occurrence_id TEXT,
                    resource_path TEXT NOT NULL,
                    content_sha256 TEXT NOT NULL,
                    offset_chars INTEGER NOT NULL DEFAULT 0,
                    returned_chars INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """, "CREATE INDEX skill_access_load ON skill_accesses(skill_load_id)", """
                CREATE TRIGGER skill_package_binding_immutable BEFORE UPDATE ON skill_package_bindings
                BEGIN SELECT RAISE(ABORT,'Skill package binding is immutable'); END
                """, """
                CREATE TRIGGER skill_package_binding_retained BEFORE DELETE ON skill_package_bindings
                BEGIN SELECT RAISE(ABORT,'Skill package binding is retained'); END
                """, """
                CREATE TRIGGER skill_access_immutable BEFORE UPDATE ON skill_accesses
                BEGIN SELECT RAISE(ABORT,'Skill access facts are immutable'); END
                """);
    }
}
