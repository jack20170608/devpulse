package io.github.devpulse.storage.jdbi;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Describes how an entity maps onto a table.
 *
 * <p>The key is a list of entity fields, not a single id: a run is keyed by one string column,
 * while a task, a dependency edge and the reuse index are keyed by two or three columns. Generated
 * {@code Long} keys remain supported for the single-column case.
 */
public class TableDescription {

    private final boolean idAutoGenerate;
    private final String name;
    private final TreeMap<String, String> fieldColumnMap;
    private final List<String> idFields;
    private final String fromClause;
    private final Class<?> entityClass;

    public String getName() {
        return name;
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    /** Entity field names that form the key, in key order. */
    public List<String> getIdFields() {
        return idFields;
    }

    /** True when the key is one column. */
    public boolean isSimpleKey() {
        return idFields.size() == 1;
    }

    public String getFromClause() {
        return fromClause;
    }

    public boolean isIdAutoGenerate() {
        return idAutoGenerate;
    }

    public TreeMap<String, String> getFieldColumnMap() {
        if (fieldColumnMap == null) {
            throw new IllegalStateException(
                    "fieldColumnMap is not initialized. Ensure it is set via builder before use.");
        }
        // Return an unmodifiable copy so callers cannot mutate the description.
        return new TreeMap<>(fieldColumnMap);
    }

    /** Column of an entity field. */
    public String column(String field) {
        var column = getFieldColumnMap().get(field);
        if (column == null) {
            throw new IllegalArgumentException("Unknown field: " + field);
        }
        return column;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean idAutoGenerate;
        private String name;
        private Map<String, String> fieldColumnMap;
        private List<String> idFields;
        private String fromClause;
        private Class<?> entityClass;

        private Builder() {}

        public Builder withIdAutoGenerate(boolean idAutoGenerate) {
            this.idAutoGenerate = idAutoGenerate;
            return this;
        }

        public Builder withName(String name) {
            this.name = name;
            return this;
        }

        public Builder withFieldColumnMap(Map<String, String> fieldColumnMap) {
            this.fieldColumnMap = fieldColumnMap == null ? null : new TreeMap<>(fieldColumnMap);
            return this;
        }

        public Builder withIdFields(List<String> idFields) {
            this.idFields = idFields == null ? null : List.copyOf(idFields);
            return this;
        }

        public Builder withIdField(String idField) {
            return withIdFields(List.of(idField));
        }

        public Builder withFromClause(String fromClause) {
            this.fromClause = fromClause;
            return this;
        }

        public Builder withEntityClass(Class<?> entityClass) {
            this.entityClass = entityClass;
            return this;
        }

        public TableDescription build() {
            return new TableDescription(
                    name, entityClass, idAutoGenerate, fromClause, fieldColumnMap, idFields);
        }
    }

    private TableDescription(
            String name,
            Class<?> entityClass,
            boolean idAutoGenerate,
            String fromClause,
            Map<String, String> fieldColumnMap,
            List<String> idFields) {
        if (idFields == null || idFields.isEmpty()) {
            throw new IllegalArgumentException("A table description needs at least one id field.");
        }
        if (idAutoGenerate && idFields.size() > 1) {
            throw new IllegalArgumentException(
                    "An auto-generated id requires exactly one id field.");
        }
        this.name = name;
        this.idAutoGenerate = idAutoGenerate;
        this.idFields = List.copyOf(idFields);
        this.entityClass = entityClass;
        if (fromClause != null && !fromClause.isBlank()) {
            this.fromClause = fromClause;
        } else {
            this.fromClause = name;
        }
        this.fieldColumnMap = fieldColumnMap != null ? new TreeMap<>(fieldColumnMap) : null;
        for (var field : this.idFields) {
            column(field);
        }
    }
}
