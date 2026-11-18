package com.bhai.billing.core;

import java.util.List;
import java.util.Map;

/** Small database access interface used by {@link BillingService}. Implemented by {@link JdbcDb} for MySQL. */
public interface Db {

    /** Rows as maps; keys are lower-case column labels. */
    List<Map<String, Object>> query(String sql, Object... args);

    default Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> r = query(sql, args);
        return r.isEmpty() ? null : r.get(0);
    }

    int update(String sql, Object... args);

    /** INSERT that returns the AUTO_INCREMENT key. */
    long insert(String sql, Object... args);

    void batch(String sql, List<Object[]> rows);

    /** Runs the work in one transaction (commit on success, rollback on any exception). */
    <T> T tx(TxWork<T> work);

    interface TxWork<T> {
        T run(Db tx);
    }
}
