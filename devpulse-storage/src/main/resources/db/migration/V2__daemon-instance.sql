-- Daemon instances move out of per-instance files and into the store.
--
-- Applied as schema version 2. task_id is the per-run node id, so an instance
-- stays addressable by the same value the run's tasks use for their logs.

CREATE TABLE IF NOT EXISTS daemon_instance (
    run_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    attempt INT NOT NULL,
    name VARCHAR(256) NOT NULL,
    project VARCHAR(1024) NOT NULL,
    bash VARCHAR(1024) NOT NULL,
    pid BIGINT NOT NULL,
    process_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ready_command VARCHAR(4096) NOT NULL,
    stop_command VARCHAR(4096),
    stop_timeout_nanos BIGINT NOT NULL,
    interval_nanos BIGINT NOT NULL,
    timeout_nanos BIGINT NOT NULL,
    check_timeout_nanos BIGINT NOT NULL,
    ready_at TIMESTAMP WITH TIME ZONE,
    stopped_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (run_id, task_id, instance_id),
    CONSTRAINT daemon_run FOREIGN KEY (run_id) REFERENCES run (id)
        ON DELETE CASCADE,
    CONSTRAINT daemon_instance_unique UNIQUE (run_id, instance_id)
);

CREATE INDEX IF NOT EXISTS daemon_task ON daemon_instance (run_id, task_id);
