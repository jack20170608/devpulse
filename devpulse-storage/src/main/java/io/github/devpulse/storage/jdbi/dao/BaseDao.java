package io.github.devpulse.storage.jdbi.dao;

import io.github.devpulse.storage.jdbi.SearchCriteria;
import io.github.devpulse.storage.jdbi.SqlGenerator;
import io.github.devpulse.storage.jdbi.page.Page;
import io.github.devpulse.storage.jdbi.page.Pageable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.HandleCallback;

/**
 * CRUD over one table, keyed by an arbitrary key object.
 *
 * <p>The key type {@code K} may be a single value class ({@code record RunKey(String id)}) or a
 * composite one ({@code record TaskKey(String runId, String name)}). Its component names must match
 * the description's id fields, because keys bind as a bean named {@code k}.
 *
 * @param <T> entity type
 * @param <K> key type, matching the table description's id fields
 */
public interface BaseDao<T, K> {

    String getCachedSql(SqlGenerator.SQL_STATEMENT sqlStatementType);

    /** Insert one entity and return its key. */
    K create(T entity);

    /**
     * The insert as a callback, so several inserts can share one transaction.
     *
     * <p>Use {@code handle.inTransaction} or {@code jdbi.useTransaction} with this rather than
     * calling {@link #create} repeatedly: each {@code create} opens its own handle.
     */
    HandleCallback<K, RuntimeException> invokeCreate(T entity);

    /** Update every non-key field of the entity identified by {@code key}. */
    int update(K key, T entity);

    int update(String sql, Map<String, Object> params);

    int update(String sql, Map<String, Object> params, Map<String, ?> listParam);

    int update(
            String sql,
            Map<String, Object> params,
            Map<String, ?> listParam,
            Map<String, Object> beanParam);

    Optional<T> findOne(K key);

    boolean exists(K key);

    List<T> find(SearchCriteria searchCriteria);

    Page<T> find(SearchCriteria searchCriteria, Pageable pageable);

    List<T> find(String sql, Map<String, Object> params, Map<String, ?> listParam);

    int count(SearchCriteria searchCriteria);

    int count(String sql, Map<String, Object> params, Map<String, ?> listParam);

    List<T> findAll();

    Page<T> findAll(Pageable pageable);

    /** Every entity matching one of the keys, in key order. */
    List<T> findAllByKeys(List<K> keys);

    long countAll();

    int delete(K key);

    /** Delete by key, one statement per key, all in one transaction. */
    int delete(List<K> keys);

    int delete(String sql, Map<String, Object> params, Map<String, ?> listParam);

    void deleteAll();
}
