package com.pomgmt.core;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Thin JDBC facade. Rows are maps with lower-case column labels. */
public interface Db {
    List<Map<String, Object>> query(String sql, Object... args);

    /** First row or null. */
    Map<String, Object> one(String sql, Object... args);

    int update(String sql, Object... args);

    /** Insert and return the generated key. */
    long insert(String sql, Object... args);

    int[] batch(String sql, List<Object[]> rows);

    /** Runs work in one transaction (joins an already open one). */
    <T> T tx(Supplier<T> work);
}
