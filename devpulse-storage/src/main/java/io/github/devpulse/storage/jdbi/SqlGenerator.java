package io.github.devpulse.storage.jdbi;

import io.github.devpulse.storage.jdbi.page.Order;
import io.github.devpulse.storage.jdbi.page.Pageable;
import io.github.devpulse.storage.jdbi.page.Sort;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * Builds SQL from a {@link TableDescription}.
 *
 * <p>Key predicates are generated from the description's id fields, so a single-column key and a
 * three-column key go through the same code. Keys bind as a bean named {@code k} and entities as a
 * bean named {@code t}, which is why no SQL in this class contains a literal value.
 */
public class SqlGenerator {

    public static final String WHERE = " WHERE ";
    public static final String AND = " AND ";
    public static final String SELECT = "SELECT ";
    public static final String FROM = "FROM ";
    public static final String DELETE = "DELETE ";
    public static final String COMMA = ", ";

    public enum SQL_STATEMENT {
        deleteAll,
        deleteByKey,
        selectAll,
        selectByKey,
        countAll,
        updateByKey,
        create
    }

    public static final String SQL_CREATE_TEMPLATE =
            """
            INSERT INTO %s (
            %s)
            VALUES (
            %s)
            """;

    public static final String SQL_SELECT_ALL =
            """
            SELECT %s FROM %s
            """;

    public static final String SQL_COUNT_ALL =
            """
            select count(*) from %s
            """;

    private final String allColumnsClause;

    public SqlGenerator(String allColumnsClause) {
        this.allColumnsClause = allColumnsClause;
    }

    public SqlGenerator() {
        this("*");
    }

    public String count(TableDescription table) {
        return SELECT + "COUNT(*) " + FROM + table.getFromClause();
    }

    public String count(TableDescription table, SearchCriteria searchCriteria) {
        return String.format(SQL_COUNT_ALL, table.getFromClause()) + searchCriteria.whereClause();
    }

    public String selectAll(TableDescription table) {
        return SELECT + allColumnsClause + " " + FROM + table.getFromClause();
    }

    public String select(TableDescription table, SearchCriteria searchCriteria) {
        return String.format(SQL_SELECT_ALL, allColumnsClause, table.getFromClause())
                + searchCriteria.whereClause();
    }

    public String select(TableDescription table, SearchCriteria searchCriteria, Pageable pageable) {
        var select =
                String.format(SQL_SELECT_ALL, allColumnsClause, table.getFromClause())
                        + searchCriteria.whereClause();
        if (pageable == null) {
            return select;
        }
        if (StringUtils.isEmpty(searchCriteria.pageableWhereClause(pageable))) {
            select = select + sortingClauseIfRequired(pageable.getSort()) + limitClause(pageable);
        }
        return select;
    }

    public String selectByKey(TableDescription table) {
        return selectAll(table) + whereByKeyClause(table);
    }

    public String deleteAll(TableDescription table) {
        return DELETE + FROM + table.getName();
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

    public String limitClause(Pageable page) {
        var offset = (page.getPageNumber() - 1) * page.getPageSize();
        return " LIMIT " + page.getPageSize() + " OFFSET " + offset;
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

    protected String sortingClauseIfRequired(Sort sort) {
        if (sort == null) {
            return "";
        }
        var orderByClause = new StringBuilder(" ORDER BY ");
        for (Iterator<Order> iterator = sort.iterator(); iterator.hasNext(); ) {
            var order = iterator.next();
            orderByClause
                    .append(validatePropertyName(order.getProperty()))
                    .append(" ")
                    .append(order.getDirection());
            if (iterator.hasNext()) {
                orderByClause.append(COMMA);
            }
        }
        return orderByClause.toString();
    }

    /**
     * Reject anything that is not a plain column identifier.
     *
     * <p>ORDER BY cannot be parameterised, so the property name is the one place where a caller's
     * string reaches the SQL text; the pattern is intentionally strict.
     */
    private String validatePropertyName(String property) {
        if (StringUtils.isBlank(property)) {
            throw new IllegalArgumentException("Property name cannot be null or empty");
        }
        if (!property.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$")) {
            throw new IllegalArgumentException(
                    "Invalid property name: "
                            + property
                            + ". Only alphanumeric, underscore, and dot allowed.");
        }
        return property;
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

    public String getAllColumnsClause() {
        return allColumnsClause;
    }

    /** Kept for callers that need to build a description-independent clause. */
    public static Map<String, String> ordered(Map<String, String> map) {
        return Objects.isNull(map) ? Map.of() : new TreeMap<>(map);
    }
}
