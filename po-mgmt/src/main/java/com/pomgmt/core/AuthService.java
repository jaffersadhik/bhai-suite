package com.pomgmt.core;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple token auth: username + SHA-256(salt+password); session tokens kept as SHA-256 hashes.
 * Roles: purchase_executive (create), purchase_manager (review/edit), approver (approve/reject),
 *        admin (full), management (view reports).
 */
public class AuthService {
    static final List<String> ROLES = List.of("purchase_executive", "purchase_manager", "approver", "admin", "management");
    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    private final Db db;
    private final ZoneId zone;

    public AuthService(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    /** ensure the admin / admin1234 account exists (create it if missing — also recovers a half-seeded database). */
    public void ensureAdmin() {
        if (db.one("SELECT id FROM users WHERE username = 'admin'") == null) {
            createUser("admin", "Administrator", "admin", "admin1234", true);
        }
    }

    public long createUser(String username, String fullName, String role, String password, boolean active) {
        String salt = U.salt();
        return db.insert("INSERT INTO users (username, full_name, role, pass_salt, pass_hash, active, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                username.toLowerCase().trim(), fullName, role, salt, U.sha256(salt + password), active ? 1 : 0, now());
    }

    public Map<String, Object> login(String username, String password) {
        String u = U.str(username).toLowerCase();
        // DEBUG (POMGMT_LOG_LEVEL=DEBUG): password is logged as length only, never in clear, so the log stays safe to share
        LOG.debug("login attempt: user='{}' passLen={}", u, U.str(password).length());
        if (u.isEmpty() || U.str(password).isEmpty()) {
            LOG.debug("login FAIL: username / password empty");
            throw new ApiException(400, "பயனர் பெயர் / கடவுச்சொல் தேவை");
        }
        Map<String, Object> row = db.one("SELECT * FROM users WHERE username = ?", u);
        if (row == null) {
            LOG.debug("login FAIL: username not found in this database: '{}'", u);
            throw new ApiException(401, "தவறான பயனர் பெயர் அல்லது கடவுச்சொல்");
        }
        boolean active = U.intv(row.get("active")) != 0;
        boolean match = U.sha256(U.str(row.get("pass_salt")) + password).equals(U.str(row.get("pass_hash")));
        String ph = U.str(row.get("pass_hash"));
        LOG.debug("login check: user='{}' found=true active={} hashMatch={} storedHashPrefix={}",
                u, active, match, ph.length() >= 8 ? ph.substring(0, 8) : ph);
        if (!active || !match) {
            LOG.debug("login FAIL: {} -> user='{}'", !active ? "account inactive" : "password mismatch (wrong password for existing user)", u);
            throw new ApiException(401, "தவறான பயனர் பெயர் அல்லது கடவுச்சொல்");
        }
        String token = U.token();
        db.update("INSERT INTO sessions (token_hash, user_id, created_at, last_seen) VALUES (?, ?, ?, ?)",
                U.sha256(token), U.lng(row.get("id")), now(), now());
        LOG.debug("login OK: user='{}' role={}", u, U.str(row.get("role")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("me", me(row));
        return out;
    }

    public Ctx ctx(String token) {
        if (U.blank(token)) {
            throw new ApiException(401, "உள்நுழையவும் (Login required)");
        }
        Map<String, Object> row = db.one(
                "SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND u.active = 1", U.sha256(token));
        if (row == null) {
            throw new ApiException(401, "மீண்டும் உள்நுழையவும் (Session expired)");
        }
        db.update("UPDATE sessions SET last_seen = ? WHERE token_hash = ?", now(), U.sha256(token));
        return new Ctx(U.lng(row.get("id")), U.str(row.get("role")), U.str(row.get("full_name")), token);
    }

    public void logout(String token) {
        if (!U.blank(token)) {
            db.update("DELETE FROM sessions WHERE token_hash = ?", U.sha256(token));
        }
    }

    public void changePassword(Ctx c, String oldPw, String newPw) {
        Map<String, Object> row = db.one("SELECT * FROM users WHERE id = ?", c.userId());
        if (row == null || !U.sha256(U.str(row.get("pass_salt")) + oldPw).equals(U.str(row.get("pass_hash")))) {
            throw new ApiException(400, "பழைய கடவுச்சொல் தவறு");
        }
        if (U.str(newPw).length() < 6) {
            throw new ApiException(400, "புதிய கடவுச்சொல் குறைந்தது 6 எழுத்து");
        }
        String salt = U.salt();
        db.update("UPDATE users SET pass_salt = ?, pass_hash = ? WHERE id = ?", salt, U.sha256(salt + newPw), c.userId());
        db.update("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?", c.userId(), U.sha256(c.token()));
    }

    private Map<String, Object> me(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", U.lng(row.get("id")));
        m.put("username", U.str(row.get("username")));
        m.put("name", U.str(row.get("full_name")));
        m.put("role", U.str(row.get("role")));
        return m;
    }

    public Map<String, Object> me(Ctx c) {
        return me(db.one("SELECT * FROM users WHERE id = ?", c.userId()));
    }

    public List<Object> users(Ctx c) {
        c.needAdmin();
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, username, full_name, role, active FROM users ORDER BY role, username")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", U.lng(r.get("id")));
            m.put("username", U.str(r.get("username")));
            m.put("name", U.str(r.get("full_name")));
            m.put("role", U.str(r.get("role")));
            m.put("active", U.intv(r.get("active")) == 1);
            out.add(m);
        }
        return out;
    }

    public void saveUser(Ctx c, Map<String, Object> in) {
        c.needAdmin();
        long id = U.lng(in.get("id"));
        String username = U.clip(in.get("username"), 30).toLowerCase();
        String name = U.clip(in.get("name"), 60);
        String role = U.str(in.get("role"));
        if (!ROLES.contains(role)) {
            throw new ApiException(400, "Role: " + String.join(" / ", ROLES));
        }
        if (username.isEmpty() || name.isEmpty()) {
            throw new ApiException(400, "பயனர் பெயர் மற்றும் முழு பெயர் தேவை");
        }
        boolean active = !Boolean.FALSE.equals(in.get("active"));
        if (id == 0) {
            String pw = U.str(in.get("password"));
            if (pw.length() < 6) {
                throw new ApiException(400, "கடவுச்சொல் குறைந்தது 6 எழுத்து");
            }
            createUser(username, name, role, pw, active);
        } else {
            db.update("UPDATE users SET username = ?, full_name = ?, role = ?, active = ? WHERE id = ?",
                    username, name, role, active ? 1 : 0, id);
            String pw = U.str(in.get("password"));
            if (!pw.isEmpty()) {
                if (pw.length() < 6) {
                    throw new ApiException(400, "கடவுச்சொல் குறைந்தது 6 எழுத்து");
                }
                String salt = U.salt();
                db.update("UPDATE users SET pass_salt = ?, pass_hash = ? WHERE id = ?", salt, U.sha256(salt + pw), id);
            }
        }
    }

    public void deleteUser(Ctx c, long id) {
        c.needAdmin();
        if (id == c.userId()) {
            throw new ApiException(400, "உங்கள் சொந்த கணக்கை நீக்க முடியாது");
        }
        db.update("DELETE FROM sessions WHERE user_id = ?", id);
        db.update("DELETE FROM users WHERE id = ?", id);
    }
}
