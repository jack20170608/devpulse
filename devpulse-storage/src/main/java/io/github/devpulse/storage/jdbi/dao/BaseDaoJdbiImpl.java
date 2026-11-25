package io.github.devpulse.storage.jdbi.dao;

import io.github.devpulse.storage.jdbi.SqlGenerator;
import io.github.devpulse.storage.jdbi.TableDescription;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.result.ResultIterable;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.SqlStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBI implementation of {@link BaseDao}.
 *
 * <p>Keys bind as a bean named {@code k} and entities as a bean named {@code t}; records use JDBI's
 * method binding because a record has no JavaBean accessors. A subclass supplies the table
 * description, the row mappers, and how to read the key out of an entity.
 */
public abstract class BaseDaoJdbiImpl<T, K> implements BaseDao<T, K> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseDaoJdbiImpl.class);

    protected final TableDescription table;
    protected final Jdbi jdbi;

    private final SqlGenerator sqlGenerator = new SqlGenerator();
    private final Function<T, K> keyOf;
    private final Map<SqlGenerator.SQL_STATEMENT, String> sqlCache = new ConcurrentHashMap<>(10);

    /**
     * @param keyOf reads the key out of an entity; required unless the table generates its own key
     */
    protected BaseDaoJdbiImpl(TableDescription table, Jdbi jdbi, Function<T, K> keyOf) {
        this.table = Objects.requireNonNull(table, "table");
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.keyOf = keyOf;
        if (!table.isIdAutoGenerate() && keyOf == null) {
            throw new IllegalArgumentException(
                    "A table without a generated key needs a key extractor.");
        }
        registerRowMappers(jdbi);
    }

    /** Register the row mappers this DAO maps results with. */
    protected abstract void registerRowMappers(Jdbi jdbi);

    @Override
    public K create(T entity) {
        return jdbi.withHandle(invokeCreate(entity));
    }

    @Override
    public HandleCallback<K, RuntimeException> invokeCreate(T entity) {
        var sql = getCachedSql(SqlGenerator.SQL_STATEMENT.create);
        LOGGER.debug("Create SQL=[{}].", sql);
        return handle -> {
            var update = handle.createUpdate(sql);
            bindEntity(update, "t", entity);
            if (!table.isIdAutoGenerate()) {
                update.execute();
                return keyOf.apply(entity);
            }
            var keyColumn = table.column(table.getIdFields().getFirst());
            return generatedKey(
                    update.executeAndReturnGeneratedKeys(keyColumn).mapTo(Long.class).one());
        };
    }

    @Override
    public int update(K key, T entity) {
        var sql = getCachedSql(SqlGenerator.SQL_STATEMENT.updateByKey);
        LOGGER.debug("Update SQL=[{}].", sql);
        return jdbi.withHandle(handle -> {
            var update = handle.createUpdate(sql);
            bindEntity(update, "t", entity);
            bindKey(update, key);
            return update.execute();
        });
    }

    @Override
    public Optional<T> findOne(K key) {
        var sql = getCachedSql(SqlGenerator.SQL_STATEMENT.selectByKey);
        LOGGER.debug("FindOne SQL=[{}].", sql);
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql);
            bindKey(query, key);
            return mapped(query).findOne();
        });
    }

    @Override
    public boolean exists(K key) {
        return findOne(key).isPresent();
    }

    @Override
    public List<T> find(String sql, Map<String, Object> params, Map<String, ?> listParam) {
        LOGGER.debug("Find sql=[{}].", sql);
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql);
            bindParamsForQuery(query, params, listParam);
            return mapped(query).list();
        });
    }

    @Override
    public List<T> findAll() {
        var sql = getCachedSql(SqlGenerator.SQL_STATEMENT.selectAll);
        return jdbi.withHandle(handle -> mapped(handle.createQuery(sql)).list());
    }

    @Override
    public long countAll() {
        var sql = getCachedSql(SqlGenerator.SQL_STATEMENT.countAll);
        return jdbi.withHandle(handle -> handle.createQuery(sql).mapTo(Long.class).one());
    }

    @Override
    public int delete(List<K> keys) {
        var sql = getCachedSql(SqlGenerator.SQL_STATEMENT.deleteByKey);
        return jdbi.inTransaction(handle -> {
            var deleted = 0;
            for (var key : keys) {
                var update = handle.createUpdate(sql);
                bindKey(update, key);
                deleted += update.execute();
            }
            return deleted;
        });
    }

    @Override
    public int delete(K key) {
        var sql = getCachedSql(SqlGenerator.SQL_STATEMENT.deleteByKey);
        return jdbi.withHandle(handle -> {
            var update = handle.createUpdate(sql);
            bindKey(update, key);
            return update.execute();
        });
    }

    private String getCachedSql(SqlGenerator.SQL_STATEMENT sqlStatementType) {
        return sqlCache.computeIfAbsent(
                sqlStatementType,
                key ->
                        switch (key) {
                            case deleteByKey -> sqlGenerator.deleteByKey(table);
                            case selectAll -> sqlGenerator.selectAll(table);
                            case selectByKey -> sqlGenerator.selectByKey(table);
                            case countAll -> sqlGenerator.count(table);
                            case updateByKey -> sqlGenerator.updateByKey(table);
                            case create -> sqlGenerator.create(table);
                        });
    }

    private void bindEntity(SqlStatement<?> statement, String prefix, T entity) {
        if (entity.getClass().isRecord()) {
            statement.bindMethods(prefix, entity);
        } else {
            statement.bindBean(prefix, entity);
        }
    }

    private void bindKey(SqlStatement<?> statement, K key) {
        if (key == null) {
            throw new IllegalArgumentException("A key is required.");
        }
        if (key.getClass().isRecord()) {
            statement.bindMethods("k", key);
        } else {
            statement.bindBean("k", key);
        }
    }

    @SuppressWarnings("unchecked")
    private K generatedKey(Long value) {
        return (K) value;
    }

    private void bindParamsForQuery(
            Query query, Map<String, Object> params, Map<String, ?> listParam) {
        if (Objects.nonNull(listParam) && !listParam.isEmpty()) {
            for (var entry : listParam.entrySet()) {
                query.bindList(entry.getKey(), (List<?>) entry.getValue());
            }
        }
        if (Objects.nonNull(params) && !params.isEmpty()) {
            params.forEach(query::bind);
        }
    }

    @SuppressWarnings("unchecked")
    private ResultIterable<T> mapped(Query query) {
        return (ResultIterable<T>) query.mapTo(entityType());
    }

    private Type entityType() {
        return Objects.isNull(table.getEntityClass())
                ? ((ParameterizedType) getClass().getGenericSuperclass())
                        .getActualTypeArguments()[0]
                : table.getEntityClass();
    }
}
