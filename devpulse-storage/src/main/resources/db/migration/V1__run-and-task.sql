-- Run and task history.
--
-- Applied as schema version 1. Never edit this file once released: an existing
-- store recorded version 1 with exactly these statements, so add the next
-- numbered migration instead.

CREATE TABLE IF NOT EXISTS run (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    project VARCHAR(1024) NOT NULL,
    target VARCHAR(256) NOT NULL,
    parallelism INT NOT NULL,
    state VARCHAR(32) NOT NULL,
    executor_pid BIGINT NOT NULL,
    executor_started_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    bash VARCHAR(1024),
    tool_version VARCHAR(128),
    parent_run_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS run_project_started
    ON run (project, started_at);

CREATE TABLE IF NOT EXISTS task (
    run_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128),
    state VARCHAR(32) NOT NULL,
    exit_code INT,
    error VARCHAR(4096),
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    log_path VARCHAR(1024),
    reused_from VARCHAR(64),
    daemon_health VARCHAR(32),
    daemon_error VARCHAR(4096),
    daemon_checked_at TIMESTAMP WITH TIME ZONE,
    sort_order INT NOT NULL,
    PRIMARY KEY (run_id, name),
    CONSTRAINT task_run FOREIGN KEY (run_id) REFERENCES run (id)
        ON DELETE CASCADE,
    CONSTRAINT task_id_unique UNIQUE (run_id, task_id)
);

CREATE TABLE IF NOT EXISTS task_need (
    run_id VARCHAR(64) NOT NULL,
    task_name VARCHAR(256) NOT NULL,
    need_name VARCHAR(256) NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (run_id, task_name, need_name),
    CONSTRAINT need_task FOREIGN KEY (run_id, task_name)
        REFERENCES task (run_id, name) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_result (
    name VARCHAR(256) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    run_id VARCHAR(64),
    finished_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (name, fingerprint),
    CONSTRAINT result_run FOREIGN KEY (run_id) REFERENCES run (id)
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS task_result_name ON task_result (name);
