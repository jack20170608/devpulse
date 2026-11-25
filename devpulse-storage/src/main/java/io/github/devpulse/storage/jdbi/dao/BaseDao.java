package io.github.devpulse.storage.jdbi.dao;

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
 * <p>Deliberately narrow: the store reads with explicit SQL through {@link #find}, so the generic
 * layer only owns statement generation for whole-entity CRUD. Queries with filtering, ordering or
 * paging belong to the caller, where the SQL stays visible.
 *
 * @param <T> entity type
 * @param <K> key type, matching the table description's id fields
 */
public interface BaseDao<T, K> {

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

    Optional<T> findOne(K key);

    boolean exists(K key);

    /** Run an explicit query, binding named parameters and named list parameters. */
    List<T> find(String sql, Map<String, Object> params, Map<String, ?> listParam);

    List<T> findAll();

    long countAll();

    int delete(K key);

    /** Delete by key, one statement per key, all in one transaction. */
    int delete(List<K> keys);
}
