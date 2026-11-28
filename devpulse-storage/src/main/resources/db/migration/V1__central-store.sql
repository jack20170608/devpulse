-- The schema, version 1: one database holds every project.
--
-- This is the whole schema, not an upgrade step. There is no migration history
-- to preserve yet, so a layout change edits this file and existing stores are
-- deleted rather than upgraded. The DROP statements make that explicit: applying
-- this file to a leftover store rebuilds it instead of blending two layouts.

DROP TABLE IF EXISTS run_event;
DROP TABLE IF EXISTS task_edge;
DROP TABLE IF EXISTS task_need;
DROP TABLE IF EXISTS daemon_instance;
DROP TABLE IF EXISTS task_result;
DROP TABLE IF EXISTS task;
DROP TABLE IF EXISTS workflow_revision;
DROP TABLE IF EXISTS workflow;
DROP TABLE IF EXISTS run;
DROP TABLE IF EXISTS project;

CREATE TABLE project (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    path VARCHAR(1024) NOT NULL,
    name VARCHAR(256) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT project_path_unique UNIQUE (path)
);

CREATE TABLE workflow (
    project_id VARCHAR(64) NOT NULL,
    workflow_id VARCHAR(256) NOT NULL,
    name VARCHAR(256),
    content CLOB NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id, workflow_id),
    CONSTRAINT workflow_project FOREIGN KEY (project_id) REFERENCES project (id)
        ON DELETE CASCADE
);

CREATE TABLE workflow_revision (
    project_id VARCHAR(64) NOT NULL,
    workflow_id VARCHAR(256) NOT NULL,
    revision INT NOT NULL,
    content CLOB NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id, workflow_id, revision),
    CONSTRAINT revision_workflow FOREIGN KEY (project_id, workflow_id)
        REFERENCES workflow (project_id, workflow_id) ON DELETE CASCADE
);

CREATE TABLE run (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    target VARCHAR(256) NOT NULL,
    workflow_id VARCHAR(256),
    workflow_path VARCHAR(1024),
    workflow_revision INT,
    workflow_hash VARCHAR(64),
    parallelism INT NOT NULL,
    state VARCHAR(32) NOT NULL,
    requested_by VARCHAR(256),
    queued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    worker_pid BIGINT,
    worker_started_at TIMESTAMP WITH TIME ZONE,
    server_boot_id VARCHAR(64),
    bash VARCHAR(1024),
    tool_version VARCHAR(128),
    parent_run_id VARCHAR(64),
    CONSTRAINT run_project FOREIGN KEY (project_id) REFERENCES project (id)
        ON DELETE CASCADE,
    CONSTRAINT run_state CHECK (state IN
        ('QUEUED', 'PENDING', 'READY', 'RUNNING', 'SUCCESS', 'FAILED',
         'SKIPPED', 'CANCELLED', 'INTERRUPTED'))
);

CREATE INDEX IF NOT EXISTS run_project_started ON run (project_id, started_at);

CREATE TABLE task (
    run_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    seq INT NOT NULL,
    fingerprint VARCHAR(128),
    state VARCHAR(32) NOT NULL,
    exit_code INT,
    error VARCHAR(4096),
    error_kind VARCHAR(32),
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    log_path VARCHAR(1024),
    reused_from VARCHAR(64),
    PRIMARY KEY (run_id, name),
    CONSTRAINT task_run FOREIGN KEY (run_id) REFERENCES run (id)
        ON DELETE CASCADE,
    CONSTRAINT task_id_unique UNIQUE (run_id, task_id),
    CONSTRAINT task_state CHECK (state IN
        ('PENDING', 'READY', 'RUNNING', 'RETRYING', 'SUCCESS',
         'FAILED', 'TIMED_OUT', 'SKIPPED', 'CANCELLED'))
);

CREATE TABLE task_edge (
    run_id VARCHAR(64) NOT NULL,
    dependent_name VARCHAR(256) NOT NULL,
    prerequisite_name VARCHAR(256) NOT NULL,
    seq INT NOT NULL,
    PRIMARY KEY (run_id, dependent_name, prerequisite_name),
    CONSTRAINT edge_dependent FOREIGN KEY (run_id, dependent_name)
        REFERENCES task (run_id, name) ON DELETE CASCADE,
    CONSTRAINT edge_prerequisite FOREIGN KEY (run_id, prerequisite_name)
        REFERENCES task (run_id, name) ON DELETE CASCADE
);

CREATE TABLE task_result (
    project_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    run_id VARCHAR(64),
    finished_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id, name, fingerprint),
    CONSTRAINT result_project FOREIGN KEY (project_id) REFERENCES project (id)
        ON DELETE CASCADE,
    CONSTRAINT result_run FOREIGN KEY (run_id) REFERENCES run (id)
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS task_result_lookup ON task_result (project_id, name);

CREATE TABLE daemon_instance (
    run_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    attempt INT NOT NULL,
    name VARCHAR(256) NOT NULL,
    bash VARCHAR(1024) NOT NULL,
    pid BIGINT NOT NULL,
    process_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ready_command VARCHAR(4096) NOT NULL,
    stop_command VARCHAR(4096),
    stop_timeout_nanos BIGINT NOT NULL,
    interval_nanos BIGINT NOT NULL,
    timeout_nanos BIGINT NOT NULL,
    check_timeout_nanos BIGINT NOT NULL,
    health VARCHAR(32),
    health_error VARCHAR(4096),
    checked_at TIMESTAMP WITH TIME ZONE,
    ready_at TIMESTAMP WITH TIME ZONE,
    stopped_at TIMESTAMP WITH TIME ZONE,
    server_boot_id VARCHAR(64),
    PRIMARY KEY (run_id, task_id, instance_id),
    CONSTRAINT daemon_run FOREIGN KEY (run_id) REFERENCES run (id)
        ON DELETE CASCADE,
    CONSTRAINT daemon_task FOREIGN KEY (run_id, task_id)
        REFERENCES task (run_id, task_id) ON DELETE CASCADE,
    CONSTRAINT daemon_instance_unique UNIQUE (run_id, instance_id)
);

CREATE INDEX IF NOT EXISTS daemon_task_index ON daemon_instance (run_id, task_id);

CREATE TABLE run_event (
    run_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    type VARCHAR(64) NOT NULL,
    payload VARCHAR(8192),
    at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (run_id, seq),
    CONSTRAINT event_run FOREIGN KEY (run_id) REFERENCES run (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS run_event_at ON run_event (at);
