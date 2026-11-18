package com.bhai.billing.core;

import java.sql.SQLException;

/** Unchecked wrapper for SQL errors. */
public class DbException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String sqlState;

    public DbException(SQLException e) {
        super(e.getMessage(), e);
        this.sqlState = e.getSQLState();
    }

    public DbException(String msg, String sqlState) {
        super(msg);
        this.sqlState = sqlState;
    }

    /** SQLSTATE class 23 = integrity constraint violation (duplicate key, foreign key, check). */
    public boolean isConstraintViolation() {
        return sqlState != null && sqlState.startsWith("23");
    }
}
