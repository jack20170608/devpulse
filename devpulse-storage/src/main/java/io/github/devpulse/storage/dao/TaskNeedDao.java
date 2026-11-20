package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.TaskNeedKey;
import io.github.devpulse.storage.TaskNeedRecord;
import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;

/** DAO for the {@code task_need} table: one row per dependency edge. */
public final class TaskNeedDao extends BaseDaoJdbiImpl<TaskNeedRecord, TaskNeedKey> {
    private static final String BY_RUN =
            "SELECT * FROM task_need WHERE run_id = :runId ORDER BY task_name, sort_order";

    public TaskNeedDao(Jdbi jdbi) {
        super(description(), jdbi, need -> key(need));
    }

    private static TaskNeedKey key(TaskNeedRecord need) {
        return new TaskNeedKey(need.runId(), need.taskName(), need.needName());
    }

    private static TableDescription description() {
        var columns = new LinkedHashMap<String, String>();
        columns.put("runId", "run_id");
        columns.put("taskName", "task_name");
        columns.put("needName", "need_name");
        columns.put("sortOrder", "sort_order");
        return TableDescription.builder()
                .withName("task_need")
                .withEntityClass(TaskNeedRecord.class)
                .withIdFields(List.of("runId", "taskName", "needName"))
                .withFieldColumnMap(columns)
                .build();
    }

    /** Every edge of one run, grouped by the task that declares it. */
    public List<TaskNeedRecord> byRun(String runId) {
        return find(BY_RUN, Map.of("runId", runId), null);
    }

    @Override
    protected void registerRowMappers(Jdbi jdbi) {
        jdbi.registerRowMapper(TaskNeedRecord.class, (result, context) -> map(result));
    }

    private static TaskNeedRecord map(ResultSet result) throws SQLException {
        return new TaskNeedRecord(
                result.getString("run_id"),
                result.getString("task_name"),
                result.getString("need_name"),
                result.getInt("sort_order"));
    }
}
