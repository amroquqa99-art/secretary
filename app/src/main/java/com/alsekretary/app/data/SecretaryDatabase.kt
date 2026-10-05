package com.alsekretary.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alsekretary.app.domain.LifeArea

class SecretaryDatabase(context: Context) : SQLiteOpenHelper(context, "alsekretary.db", null, 7) {
    override fun onCreate(db: SQLiteDatabase) {
        createV1Tables(db)
        createV2Tables(db)
        seedAreas(db)
        createV3Tables(db)
        createV4Tables(db)
        createV5Tables(db)
        createV6Tables(db)
        createV7Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2Tables(db)
        if (oldVersion < 3) createV3Tables(db)
        if (oldVersion < 4) createV4Tables(db)
        if (oldVersion < 5) createV5Tables(db)
        if (oldVersion < 6) createV6Tables(db)
        if (oldVersion < 7) createV7Tables(db)
    }

    private fun createV1Tables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE areas(
                id TEXT PRIMARY KEY,
                code TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                sort_order INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE goals(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                area_code TEXT NOT NULL,
                specific TEXT NOT NULL,
                metric_name TEXT NOT NULL,
                target_value REAL,
                current_value REAL,
                unit TEXT,
                deadline INTEGER,
                relevant_reason TEXT NOT NULL,
                achievable_note TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE projects(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                outcome TEXT NOT NULL,
                goal_id TEXT,
                status TEXT NOT NULL,
                progress REAL NOT NULL DEFAULT 0,
                deadline INTEGER,
                load_score INTEGER NOT NULL DEFAULT 0,
                override_reason TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE tasks(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                project_id TEXT,
                status TEXT NOT NULL,
                scheduled_at INTEGER,
                deadline INTEGER,
                estimated_minutes INTEGER,
                actual_minutes INTEGER,
                definition_of_done TEXT,
                priority INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE behavior_events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                entity_type TEXT,
                entity_id TEXT,
                payload TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE decisions(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                reason TEXT NOT NULL,
                evidence TEXT NOT NULL,
                expected_effect TEXT NOT NULL,
                risk TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS calendar_events(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                type TEXT NOT NULL,
                start_at INTEGER NOT NULL,
                end_at INTEGER,
                all_day INTEGER NOT NULL DEFAULT 0,
                linked_entity_type TEXT,
                linked_entity_id TEXT,
                notes TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_calendar_start ON calendar_events(start_at)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notes(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                markdown TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_updated ON notes(updated_at)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS focus_sessions(
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                mode TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                target_minutes INTEGER,
                blocked_packages TEXT NOT NULL DEFAULT '',
                exit_reason TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_focus_started ON focus_sessions(started_at)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS project_feedback(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id TEXT NOT NULL,
                classifier_kind TEXT NOT NULL,
                classifier_confidence REAL NOT NULL,
                override_reason TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun createV3Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS habits(id TEXT PRIMARY KEY,title TEXT NOT NULL,area TEXT NOT NULL,target INTEGER NOT NULL CHECK(target BETWEEN 1 AND 7),archived INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS habit_checks(habit_id TEXT NOT NULL,day TEXT NOT NULL,PRIMARY KEY(habit_id,day))")
        db.execSQL("CREATE TABLE IF NOT EXISTS weekly_reviews(id TEXT PRIMARY KEY,week TEXT NOT NULL UNIQUE,wins TEXT NOT NULL,blockers TEXT NOT NULL,next_action TEXT NOT NULL,energy INTEGER NOT NULL CHECK(energy BETWEEN 1 AND 5))")
        db.execSQL("CREATE TABLE IF NOT EXISTS preferences(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }

    private fun createV4Tables(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN repeat_days INTEGER CHECK(repeat_days BETWEEN 1 AND 365)")
        db.execSQL("ALTER TABLE tasks ADD COLUMN generated_from TEXT")
        db.execSQL("CREATE UNIQUE INDEX idx_task_occurrence ON tasks(generated_from) WHERE generated_from IS NOT NULL")
        db.execSQL("CREATE TABLE task_dependencies(task_id TEXT NOT NULL,prerequisite_id TEXT NOT NULL,PRIMARY KEY(task_id,prerequisite_id),CHECK(task_id!=prerequisite_id))")
        db.execSQL("CREATE TABLE milestones(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,title TEXT NOT NULL,due_at INTEGER,completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)))")
        db.execSQL("CREATE INDEX idx_milestones_project ON milestones(project_id)")
    }

    private fun createV5Tables(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN parent_id TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN failure_category TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN failure_reason TEXT")
        db.execSQL("ALTER TABLE goals ADD COLUMN started_at INTEGER")
        db.execSQL("UPDATE goals SET started_at=created_at")
        db.execSQL("CREATE TABLE goal_observations(id INTEGER PRIMARY KEY AUTOINCREMENT,goal_id TEXT NOT NULL,at INTEGER NOT NULL,fraction REAL NOT NULL)")
        db.execSQL("CREATE TABLE daily_checks(day TEXT PRIMARY KEY,sleep_hours REAL,energy INTEGER,wins TEXT NOT NULL,obstacles TEXT NOT NULL,next_action TEXT NOT NULL)")
        db.execSQL("CREATE TABLE project_items(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,detail TEXT NOT NULL,resolved INTEGER NOT NULL DEFAULT 0,uri TEXT)")
        db.execSQL("CREATE TABLE task_failures(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,category TEXT NOT NULL,reason TEXT NOT NULL,at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE social_outbox(id TEXT PRIMARY KEY,method TEXT NOT NULL,path TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'PENDING',error TEXT,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE social_cache(key TEXT PRIMARY KEY,json TEXT NOT NULL,updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_goal_observations ON goal_observations(goal_id,at)")
        db.execSQL("CREATE INDEX idx_task_failures ON task_failures(at)")
    }

    private fun createV6Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE assistant_messages(id TEXT PRIMARY KEY,role TEXT NOT NULL CHECK(role IN ('USER','ASSISTANT')),content TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE assistant_actions(id TEXT PRIMARY KEY,payload TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('PENDING','DONE','CANCELLED')),created_at INTEGER NOT NULL,executed_at INTEGER,result TEXT)")
    }

    private fun createV7Tables(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE goals ADD COLUMN horizon TEXT NOT NULL DEFAULT 'YEAR' CHECK(horizon IN ('LIFETIME','TEN_YEARS','YEAR','MONTH','WEEK','DAY'))")
        db.execSQL("ALTER TABLE goals ADD COLUMN parent_goal_id TEXT")
        db.execSQL("CREATE INDEX idx_goal_parent ON goals(parent_goal_id)")
    }

    private fun seedAreas(db: SQLiteDatabase) {
        LifeArea.entries.forEachIndexed { index, area ->
            db.execSQL(
                "INSERT INTO areas(id,code,name,sort_order) VALUES(?,?,?,?)",
                arrayOf<Any>("area-${area.name.lowercase()}", area.name, area.arabicName, index)
            )
        }
    }

}
