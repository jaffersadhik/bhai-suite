package com.bhai.billing.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/** {@link Db} on plain JDBC (MySQL Connector/J through Spring Boot's HikariCP pool). */
public class JdbcDb implements Db {
    private final DataSource ds;
    private final Connection fixed;   // set inside a transaction

    public JdbcDb(DataSource ds) {
        this.ds = ds;
        this.fixed = null;
    }

    private JdbcDb(Connection c) {
        this.ds = null;
        this.fixed = c;
    }

    private interface ConnWork<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T use(ConnWork<T> w) {
        try {
            if (fixed != null) return w.run(fixed);
            try (Connection c = ds.getConnection()) {
                return w.run(c);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    @Override
    public List<Map<String, Object>> query(String sql, Object... args) {
        return use(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= n; i++) {
                            Object v = rs.getObject(i);
                            if (v instanceof Timestamp) v = ((Timestamp) v).toLocalDateTime();
                            row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), v);
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public int update(String sql, Object... args) {
        return use(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public long insert(String sql, Object... args) {
        return use(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, args);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    if (!k.next()) throw new SQLException("No generated key");
                    return k.getLong(1);
                }
            }
        });
    }

    @Override
    public void batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) return;
        use(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int pending = 0;
                for (Object[] r : rows) {
                    bind(ps, r);
                    ps.addBatch();
                    if (++pending == 500) { ps.executeBatch(); pending = 0; }
                }
                if (pending > 0) ps.executeBatch();
                return null;
            }
        });
    }

    @Override
    public <T> T tx(TxWork<T> work) {
        if (fixed != null) return work.run(this);
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T r = work.run(new JdbcDb(c));
                c.commit();
                return r;
            } catch (RuntimeException | Error e) {
                try { c.rollback(); } catch (SQLException ignore) { /* keep original error */ }
                throw e;
            } finally {
                try { c.setAutoCommit(auto); } catch (SQLException ignore) { /* connection goes back to pool */ }
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }
}
