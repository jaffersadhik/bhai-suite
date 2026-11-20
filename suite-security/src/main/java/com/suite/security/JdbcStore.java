package com.suite.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * MySQL 8 store. Two small tables are created in the application's own database ("CREATE TABLE IF NOT EXISTS", safe on every start):
 * ip_fail_day (counters per IP and day) and ip_block (blocks and their history). Plain statements only (no vendor-only upsert syntax).
 */
public final class JdbcStore implements Store {
    private final DataSource ds;

    public JdbcStore(DataSource ds) {
        this.ds = ds;
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T tx(Work<T> w) {
        try (Connection c = ds.getConnection()) {
            return w.run(c);
        } catch (SQLException e) {
            throw new IllegalStateException("ip guard database error: " + e.getMessage(), e);
        }
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }

    @Override
    public void init() {
        tx(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS ip_fail_day (ip VARCHAR(64) NOT NULL, day DATE NOT NULL, fails INT NOT NULL DEFAULT 0, "
                        + "captcha_fails INT NOT NULL DEFAULT 0, last_at DATETIME NOT NULL, PRIMARY KEY (ip, day)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.execute("CREATE TABLE IF NOT EXISTS ip_block (ip VARCHAR(64) NOT NULL PRIMARY KEY, blocked_at DATETIME NOT NULL, reason VARCHAR(160) NOT NULL, "
                        + "fails INT NOT NULL, last_user VARCHAR(60) NULL, active TINYINT(1) NOT NULL DEFAULT 1, unblocked_at DATETIME NULL, unblocked_by VARCHAR(80) NULL, "
                        + "KEY idx_ip_block_active (active, blocked_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            return null;
        });
    }

    @Override
    public Counts bump(String ip, LocalDate day, boolean captcha, LocalDateTime now) {
        return tx(c -> {
            int f = captcha ? 0 : 1, cf = captcha ? 1 : 0;
            int n = update(c, "UPDATE ip_fail_day SET fails = fails + ?, captcha_fails = captcha_fails + ?, last_at = ? WHERE ip = ? AND day = ?", f, cf, Timestamp.valueOf(now), ip, java.sql.Date.valueOf(day));
            if (n == 0) {
                try {
                    update(c, "INSERT INTO ip_fail_day (ip, day, fails, captcha_fails, last_at) VALUES (?,?,?,?,?)", ip, java.sql.Date.valueOf(day), f, cf, Timestamp.valueOf(now));
                } catch (SQLIntegrityConstraintViolationException dup) {          // a parallel request created the row first
                    update(c, "UPDATE ip_fail_day SET fails = fails + ?, captcha_fails = captcha_fails + ?, last_at = ? WHERE ip = ? AND day = ?", f, cf, Timestamp.valueOf(now), ip, java.sql.Date.valueOf(day));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT fails, captcha_fails FROM ip_fail_day WHERE ip = ? AND day = ?")) {
                ps.setString(1, ip);
                ps.setDate(2, java.sql.Date.valueOf(day));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Counts(rs.getInt(1), rs.getInt(2)) : new Counts(f, cf);
                }
            }
        });
    }

    private static Block map(ResultSet rs) throws SQLException {
        Timestamp ub = rs.getTimestamp("unblocked_at");
        return new Block(rs.getString("ip"), rs.getTimestamp("blocked_at").toLocalDateTime(), rs.getString("reason"), rs.getInt("fails"), rs.getString("last_user"),
                rs.getInt("active") == 1, ub == null ? null : ub.toLocalDateTime(), rs.getString("unblocked_by"));
    }

    @Override
    public Block findBlock(String ip) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ip, blocked_at, reason, fails, last_user, active, unblocked_at, unblocked_by FROM ip_block WHERE ip = ? AND active = 1")) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        });
    }

    @Override
    public void block(String ip, String reason, int fails, String lastUser, LocalDateTime now) {
        tx(c -> {
            String user = lastUser == null ? null : (lastUser.length() > 60 ? lastUser.substring(0, 60) : lastUser);
            int n = update(c, "UPDATE ip_block SET blocked_at = ?, reason = ?, fails = ?, last_user = ?, active = 1, unblocked_at = NULL, unblocked_by = NULL WHERE ip = ?",
                    Timestamp.valueOf(now), reason, fails, user, ip);
            if (n == 0) {
                try {
                    update(c, "INSERT INTO ip_block (ip, blocked_at, reason, fails, last_user, active) VALUES (?,?,?,?,?,1)", ip, Timestamp.valueOf(now), reason, fails, user);
                } catch (SQLIntegrityConstraintViolationException dup) {
                    update(c, "UPDATE ip_block SET blocked_at = ?, reason = ?, fails = ?, last_user = ?, active = 1, unblocked_at = NULL, unblocked_by = NULL WHERE ip = ?",
                            Timestamp.valueOf(now), reason, fails, user, ip);
                }
            }
            return null;
        });
    }

    @Override
    public boolean unblock(String ip, String by, LocalDateTime now) {
        return tx(c -> update(c, "UPDATE ip_block SET active = 0, unblocked_at = ?, unblocked_by = ? WHERE ip = ? AND active = 1", Timestamp.valueOf(now),
                by == null ? null : (by.length() > 80 ? by.substring(0, 80) : by), ip) > 0);
    }

    @Override
    public List<Block> blocks() {
        return tx(c -> {
            List<Block> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT ip, blocked_at, reason, fails, last_user, active, unblocked_at, unblocked_by FROM ip_block "
                         + "ORDER BY active DESC, COALESCE(unblocked_at, blocked_at) DESC LIMIT 100")) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        });
    }

    @Override
    public void clearDay(String ip, LocalDate day) {
        tx(c -> update(c, "DELETE FROM ip_fail_day WHERE ip = ? AND day = ?", ip, java.sql.Date.valueOf(day)));
    }

    @Override
    public void purgeBefore(LocalDate day) {
        tx(c -> update(c, "DELETE FROM ip_fail_day WHERE day < ?", java.sql.Date.valueOf(day)));
    }
}
