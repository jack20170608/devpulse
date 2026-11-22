package io.github.devpulse.storage.jdbi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Describes how an entity maps onto a table.
 *
 * <p>The key is a list of entity fields, not a single id: a run is keyed by one string column,
 * while a task, a dependency edge and the reuse index are keyed by two or three columns. Generated
 * {@code Long} keys remain supported for the single-column case.
 *
 * <p>Columns are derived from the entity's record components: a camel-cased component maps to the
 * snake-cased column of the same name, which is the convention every table here follows. Name the
 * exceptions with {@link Builder#withFieldColumn} and leave out components that are not columns
 * with {@link Builder#withoutField}. The component list is therefore the single source of truth,
 * and a new component cannot silently miss its column.
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
        private final Map<String, String> columnOverrides = new TreeMap<>();
        private final TreeSet<String> excludedFields = new TreeSet<>();
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

        /**
         * Use an explicit field-to-column map instead of deriving the columns.
         *
         * <p>Kept for tables whose columns do not follow the naming convention; the DAOs rely on
         * the derived form.
         */
        public Builder withFieldColumnMap(Map<String, String> fieldColumnMap) {
            this.fieldColumnMap = fieldColumnMap == null ? null : new TreeMap<>(fieldColumnMap);
            return this;
        }

        /** Name the column of one field when it does not follow the naming convention. */
        public Builder withFieldColumn(String field, String column) {
            columnOverrides.put(field, column);
            return this;
        }

        /** Leave out a record component that is not a column, such as a child collection. */
        public Builder withoutField(String field) {
            excludedFields.add(field);
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
            var columns = fieldColumnMap;
            if (columns == null) {
                if (entityClass == null) {
                    throw new IllegalArgumentException(
                            "A table description needs an entity class or a field-to-column map.");
                }
                columns = derive(entityClass, columnOverrides, excludedFields);
            }
            return new TableDescription(
                    name, entityClass, idAutoGenerate, fromClause, columns, idFields);
        }

        private static Map<String, String> derive(
                Class<?> entityClass, Map<String, String> overrides, TreeSet<String> excluded) {
            var components = entityClass.getRecordComponents();
            if (components == null || components.length == 0) {
                throw new IllegalArgumentException(
                        "Cannot derive columns for "
                                + entityClass.getName()
                                + ": it is not a record, so name them with withFieldColumnMap.");
            }
            var columns = new LinkedHashMap<String, String>();
            for (var component : components) {
                var field = component.getName();
                if (!excluded.contains(field)) {
                    columns.put(field, overrides.getOrDefault(field, snake(field)));
                }
            }
            return columns;
        }

        private static String snake(String field) {
            var result = new StringBuilder(field.length() + 8);
            for (var index = 0; index < field.length(); index++) {
                var character = field.charAt(index);
                if (Character.isUpperCase(character)) {
                    result.append('_').append(Character.toLowerCase(character));
                } else {
                    result.append(character);
                }
            }
            return result.toString();
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
