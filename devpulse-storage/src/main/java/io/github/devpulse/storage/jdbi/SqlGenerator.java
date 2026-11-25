package io.github.devpulse.storage.jdbi;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the whole-entity CRUD statements from a {@link TableDescription}.
 *
 * <p>Key predicates are generated from the description's id fields, so a single-column key and a
 * composite key go through the same code. Keys bind as a bean named {@code k} and entities as a
 * bean named {@code t}, which is why no SQL in this class contains a literal value.
 *
 * <p>Filtering, ordering and paging are deliberately absent. Callers write those queries through
 * {@code BaseDao.find}, so the SQL stays visible where the query shape is decided.
 */
public class SqlGenerator {

    private static final String WHERE = " WHERE ";
    private static final String AND = " AND ";
    private static final String SELECT = "SELECT ";
    private static final String FROM = "FROM ";
    private static final String DELETE = "DELETE ";
    private static final String COMMA = ", ";

    private static final String SQL_CREATE_TEMPLATE =
            """
            INSERT INTO %s (
            %s)
            VALUES (
            %s)
            """;

    /** The statements this generator can build; the DAO caches one string per value. */
    public enum SQL_STATEMENT {
        deleteByKey,
        selectAll,
        selectByKey,
        countAll,
        updateByKey,
        create
    }

    public String count(TableDescription table) {
        return SELECT + "COUNT(*) " + FROM + table.getFromClause();
    }

    public String selectAll(TableDescription table) {
        return SELECT + "* " + FROM + table.getFromClause();
    }

    public String selectByKey(TableDescription table) {
        return selectAll(table) + whereByKeyClause(table);
    }

    public String deleteByKey(TableDescription table) {
        return DELETE + FROM + table.getName() + whereByKeyClause(table);
    }

    public String updateByKey(TableDescription table) {
        return createUpdateStatement(table) + whereByKeyClause(table);
    }

    public String create(TableDescription table) {
        var columns = table.getFieldColumnMap();
        var columnBuilder = new StringBuilder();
        var valueBuilder = new StringBuilder();
        for (Iterator<Map.Entry<String, String>> iterator = columns.entrySet().iterator();
                iterator.hasNext(); ) {
            var entry = iterator.next();
            var fieldName = entry.getKey();
            var isIdColumn = fieldName.equals(table.getIdFields().getFirst());
            if (table.isIdAutoGenerate() && isIdColumn) {
                continue;
            }
            columnBuilder.append(entry.getValue());
            valueBuilder.append(":t.").append(fieldName);
            if (iterator.hasNext()) {
                columnBuilder.append(COMMA);
                valueBuilder.append(COMMA);
            }
        }
        return String.format(SQL_CREATE_TEMPLATE, table.getName(), columnBuilder, valueBuilder);
    }

    /** {@code WHERE a = :k.a AND b = :k.b} for every id field, in key order. */
    private String whereByKeyClause(TableDescription table) {
        var whereClause = new StringBuilder(WHERE);
        var fields = table.getIdFields();
        for (var index = 0; index < fields.size(); index++) {
            var field = fields.get(index);
            if (index > 0) {
                whereClause.append(AND);
            }
            whereClause.append(table.column(field)).append(" = :k.").append(field);
        }
        return whereClause.toString();
    }

    private String createUpdateStatement(TableDescription table) {
        var updateQuery = new StringBuilder("UPDATE " + table.getName() + " SET ");
        var keyFields = Set.copyOf(table.getIdFields());
        Set<Map.Entry<String, String>> nonIdFieldSet =
                table.getFieldColumnMap().entrySet().stream()
                        .filter(entry -> !keyFields.contains(entry.getKey()))
                        .collect(Collectors.toCollection(() -> new java.util.LinkedHashSet<>()));
        for (Iterator<Map.Entry<String, String>> iterator = nonIdFieldSet.iterator();
                iterator.hasNext(); ) {
            var fieldEntry = iterator.next();
            updateQuery.append(fieldEntry.getValue()).append(" = :t.").append(fieldEntry.getKey());
            if (iterator.hasNext()) {
                updateQuery.append(COMMA);
            }
        }
        return updateQuery.toString();
    }
}
