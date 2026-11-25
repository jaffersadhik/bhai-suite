package com.mhotel.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;

public class JdbcDb implements Db {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource ds;
    private final ThreadLocal<Connection> current = new ThreadLocal<>();

    public JdbcDb(DataSource ds) {
        this.ds = ds;
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T with(String sql, Work<T> work) {
        Connection tx = current.get();
        try {
            if (tx != null) {
                return work.run(tx);
            }
            try (Connection c = ds.getConnection()) {
                return work.run(c);
            }
        } catch (SQLException e) {
            throw new DbException(e.getMessage() + " [" + sql + "]", e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) {
                ps.setObject(i + 1, null);
            } else if (a instanceof LocalDate d) {
                ps.setObject(i + 1, d);
            } else if (a instanceof LocalDateTime t) {
                ps.setObject(i + 1, t);
            } else {
                ps.setObject(i + 1, a);
            }
        }
    }

    static Object norm(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().format(TS);
        }
        if (v instanceof LocalDateTime t) {
            return t.format(TS);
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toString();
        }
        if (v instanceof BigDecimal b) {
            BigDecimal s = b.stripTrailingZeros();
            if (s.scale() <= 0 && s.precision() - s.scale() <= 18) {
                return s.longValue();
            }
            return b.doubleValue();
        }
        if (v instanceof BigInteger b) {
            return b.longValue();
        }
        return v;
    }

    @Override
    public List<Map<String, Object>> query(String sql, Object... args) {
        return with(sql, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= n; i++) {
                            row.put(md.getColumnLabel(i).toLowerCase(), norm(rs.getObject(i)));
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public int update(String sql, Object... args) {
        return with(sql, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public long insert(String sql, Object... args) {
        return with(sql, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, args);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public int[] batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return new int[0];
        }
        return with(sql, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Object[] r : rows) {
                    bind(ps, r);
                    ps.addBatch();
                }
                return ps.executeBatch();
            }
        });
    }

    @Override
    public <T> T tx(Supplier<T> work) {
        if (current.get() != null) {
            return work.get();
        }
        Connection c;
        try {
            c = ds.getConnection();
            c.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DbException(e.getMessage(), e);
        }
        current.set(c);
        try {
            T result = work.get();
            c.commit();
            return result;
        } catch (RuntimeException | Error e) {
            try {
                c.rollback();
            } catch (SQLException ignore) {
                // the original failure is more useful
            }
            throw e;
        } catch (SQLException e) {
            throw new DbException(e.getMessage(), e);
        } finally {
            current.remove();
            try {
                c.setAutoCommit(true);
                c.close();
            } catch (SQLException ignore) {
                // connection is going away anyway
            }
        }
    }
}
