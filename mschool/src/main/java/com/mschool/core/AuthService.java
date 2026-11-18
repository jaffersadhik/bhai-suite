package com.mschool.core;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Login, sessions, users and (for the platform admin) institutions. */
public class AuthService extends Base {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,40}");
    private static final int TOKEN_DAYS = 30;

    public AuthService(Db db, ZoneId zone) {
        super(db, zone);
    }

    // ---------------------------------------------------------------- passwords

    static String hash(String pass, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), HexFormat.of().parseHex(salt), 120_000, 256);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(out);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static boolean sameHash(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** Creates admin / admin123 on an empty database. */
    public void ensureAdmin() {
        Map<String, Object> r = db.one("SELECT COUNT(*) n FROM users");
        if (U.n(r, "n") == 0) {
            String salt = randomHex(16);
            db.insert("INSERT INTO users (username, pass_salt, pass_hash, display_name, user_type, active, created_at) VALUES (?,?,?,?,?,1,?)",
                    "admin", salt, hash("admin123", salt), "Admin", "admin", LocalDateTime.now(zone));
        }
    }

    // ---------------------------------------------------------------- sessions

    public Map<String, Object> login(String user, String pass) {
        if (U.s(user) == null || pass == null || pass.isEmpty()) {
            throw new ApiException(400, "பயனர் பெயர் மற்றும் கடவுச்சொல் தேவை");
        }
        Map<String, Object> u = db.one("SELECT id, pass_salt, pass_hash, active FROM users WHERE username = ?", user.trim().toLowerCase());
        if (u == null || U.l(u.get("active"), 0) == 0 || !sameHash(hash(pass, (String) u.get("pass_salt")), (String) u.get("pass_hash"))) {
            throw new ApiException(401, "பயனர் பெயர் அல்லது கடவுச்சொல் தவறு");
        }
        LocalDateTime now = LocalDateTime.now(zone);
        db.update("DELETE FROM auth_tokens WHERE expires_at < ?", now);
        String token = randomHex(24);
        db.update("INSERT INTO auth_tokens (token, user_id, inst_id, expires_at) VALUES (?,?,NULL,?)", token, u.get("id"), now.plusDays(TOKEN_DAYS));
        Ctx c = ctx(token);
        Map<String, Object> out = me(c);
        out.put("token", token);
        return out;
    }

    public Ctx ctx(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(401, "மீண்டும் உள்நுழையவும்");
        }
        Map<String, Object> r = db.one(
                "SELECT t.inst_id tinst, u.id, u.user_type, u.inst_id uinst, u.staff_id, u.display_name, u.active "
                        + "FROM auth_tokens t JOIN users u ON u.id = t.user_id WHERE t.token = ? AND t.expires_at > ?",
                token, LocalDateTime.now(zone));
        if (r == null || U.l(r.get("active"), 0) == 0) {
            throw new ApiException(401, "மீண்டும் உள்நுழையவும்");
        }
        String type = (String) r.get("user_type");
        Long inst = "admin".equals(type) ? U.lo(r.get("tinst")) : U.lo(r.get("uinst"));
        return new Ctx(U.n(r, "id"), type, inst, U.lo(r.get("staff_id")), (String) r.get("display_name"), token);
    }

    public Map<String, Object> me(Ctx c) {
        Map<String, Object> out = U.map("user", U.map("id", c.userId(), "name", c.name(), "type", c.type(), "staffId", c.staffId()));
        Map<String, Object> inst = null;
        Map<String, Object> year = null;
        if (c.instId() != null) {
            inst = db.one("SELECT id, name, kind, phone, address, weekly_off FROM institutions WHERE id = ?", c.instId());
            year = currentYear(c.instId());
        }
        out.put("inst", inst);
        out.put("year", year);
        return out;
    }

    public void logout(Ctx c) {
        db.update("DELETE FROM auth_tokens WHERE token = ?", c.token());
    }

    public void changePassword(Ctx c, String oldPass, String newPass) {
        if (newPass == null || newPass.length() < 6) {
            throw new ApiException(400, "புதிய கடவுச்சொல் குறைந்தது 6 எழுத்துகள் இருக்க வேண்டும்");
        }
        Map<String, Object> u = mustOne("பயனர்", "SELECT pass_salt, pass_hash FROM users WHERE id = ?", c.userId());
        if (oldPass == null || !sameHash(hash(oldPass, (String) u.get("pass_salt")), (String) u.get("pass_hash"))) {
            throw new ApiException(400, "பழைய கடவுச்சொல் தவறு");
        }
        String salt = randomHex(16);
        db.update("UPDATE users SET pass_salt = ?, pass_hash = ? WHERE id = ?", salt, hash(newPass, salt), c.userId());
        db.update("DELETE FROM auth_tokens WHERE user_id = ? AND token <> ?", c.userId(), c.token());
    }

    // ---------------------------------------------------------------- users of an institution

    public List<Map<String, Object>> users(Ctx c) {
        c.needHead();
        return db.query("SELECT u.id, u.username, u.display_name, u.user_type, u.staff_id, u.active, s.name staff_name "
                + "FROM users u LEFT JOIN staff s ON s.id = u.staff_id WHERE u.inst_id = ? ORDER BY u.user_type, u.display_name", c.inst());
    }

    public Map<String, Object> saveUser(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        Long id = U.lo(b.get("id"));
        String username = U.req(b.get("username"), 40, "பயனர் பெயர்").toLowerCase();
        if (!USERNAME.matcher(username).matches()) {
            throw new ApiException(400, "பயனர் பெயர்: 3-40 எழுத்துகள் (a-z, 0-9, . _ -)");
        }
        String name = U.req(b.get("name"), 80, "பெயர்");
        String type = U.pick(b.get("type"), null, "வகை", "principal", "office", "teacher");
        if (type == null) {
            throw new ApiException(400, "பயனர் வகை தேவை");
        }
        if (!c.admin() && type.equals("principal")) {
            throw new ApiException(403, "முதல்வர் பயனரை நிர்வாகி மட்டுமே உருவாக்க முடியும்");
        }
        Long staffId = U.lo(b.get("staffId"));
        if (type.equals("teacher") && staffId == null) {
            throw new ApiException(400, "ஆசிரியர் பயனருக்கு ஆசிரியர் பதிவை தேர்வு செய்யவும்");
        }
        if (staffId != null) {
            belongs("staff", staffId, inst, "ஊழியர்");
        }
        String pass = b.get("password") == null ? null : b.get("password").toString();
        boolean active = !b.containsKey("active") || U.b(b.get("active"));
        Map<String, Object> clash = db.one("SELECT id FROM users WHERE username = ?" + (id != null ? " AND id <> ?" : ""),
                id != null ? new Object[]{username, id} : new Object[]{username});
        if (clash != null) {
            throw new ApiException(409, "இந்த பயனர் பெயர் ஏற்கனவே உள்ளது");
        }
        if (id == null) {
            if (pass == null || pass.length() < 6) {
                throw new ApiException(400, "கடவுச்சொல் குறைந்தது 6 எழுத்துகள்");
            }
            String salt = randomHex(16);
            long nid = db.insert("INSERT INTO users (username, pass_salt, pass_hash, display_name, user_type, inst_id, staff_id, active, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    username, salt, hash(pass, salt), name, type, inst, staffId, active ? 1 : 0, LocalDateTime.now(zone));
            return U.map("id", nid);
        }
        Map<String, Object> old = mustOne("பயனர்", "SELECT id, user_type FROM users WHERE id = ? AND inst_id = ?", id, inst);
        if (!c.admin() && "principal".equals(old.get("user_type"))) {
            throw new ApiException(403, "இதற்கு அனுமதி இல்லை");
        }
        db.update("UPDATE users SET username = ?, display_name = ?, user_type = ?, staff_id = ?, active = ? WHERE id = ?",
                username, name, type, staffId, active ? 1 : 0, id);
        if (pass != null && !pass.isEmpty()) {
            if (pass.length() < 6) {
                throw new ApiException(400, "கடவுச்சொல் குறைந்தது 6 எழுத்துகள்");
            }
            String salt = randomHex(16);
            db.update("UPDATE users SET pass_salt = ?, pass_hash = ? WHERE id = ?", salt, hash(pass, salt), id);
        }
        db.update("DELETE FROM auth_tokens WHERE user_id = ?", id);
        return U.map("id", id);
    }

    public void deleteUser(Ctx c, long id) {
        c.needHead();
        if (id == c.userId()) {
            throw new ApiException(400, "உங்கள் சொந்த பயனரை நீக்க முடியாது");
        }
        Map<String, Object> u = mustOne("பயனர்", "SELECT user_type FROM users WHERE id = ? AND inst_id = ?", id, c.inst());
        if (!c.admin() && "principal".equals(u.get("user_type"))) {
            throw new ApiException(403, "இதற்கு அனுமதி இல்லை");
        }
        db.update("DELETE FROM users WHERE id = ?", id);
    }

    // ---------------------------------------------------------------- institutions

    public Map<String, Object> adminOverview(Ctx c) {
        c.needAdmin();
        List<Map<String, Object>> list = db.query(
                "SELECT i.id, i.name, i.kind, i.phone, i.address, i.weekly_off, "
                        + "(SELECT COUNT(*) FROM students s WHERE s.inst_id = i.id AND s.status = 'active') students, "
                        + "(SELECT COUNT(*) FROM staff t WHERE t.inst_id = i.id AND t.status = 'active') staff_count, "
                        + "(SELECT GROUP_CONCAT(u.username SEPARATOR ', ') FROM users u WHERE u.inst_id = i.id AND u.user_type = 'principal') principals "
                        + "FROM institutions i ORDER BY i.name");
        return U.map("institutions", list);
    }

    public Map<String, Object> saveInstitution(Ctx c, Map<String, Object> b) {
        Long id = U.lo(b.get("id"));
        if (id == null) {
            c.needAdmin();
        } else if (!c.admin()) {
            c.needHead();
            id = c.inst();
        }
        String name = U.req(b.get("name"), 120, "நிறுவனத்தின் பெயர்");
        String kind = U.pick(b.get("kind"), "school", "வகை", "school", "college", "university");
        String phone = U.s(b.get("phone"), 20, "தொலைபேசி");
        String address = U.s(b.get("address"), 255, "முகவரி");
        int weeklyOff = U.i(b.get("weeklyOff"), 7);
        if (weeklyOff < 0 || weeklyOff > 7) {
            throw new ApiException(400, "வாராந்திர விடுமுறை நாள் தவறு");
        }
        if (id != null) {
            Long fid = id;
            mustOne("நிறுவனம்", "SELECT id FROM institutions WHERE id = ?", fid);
            db.update("UPDATE institutions SET name = ?, kind = ?, phone = ?, address = ?, weekly_off = ? WHERE id = ?",
                    name, kind, phone, address, weeklyOff, fid);
            return U.map("id", fid);
        }
        String pUser = U.s(b.get("principalUser"));
        String pPass = b.get("principalPass") == null ? null : b.get("principalPass").toString();
        String pName = U.s(b.get("principalName"));
        if (pUser != null) {
            pUser = pUser.toLowerCase();
            if (!USERNAME.matcher(pUser).matches()) {
                throw new ApiException(400, "முதல்வர் பயனர் பெயர்: 3-40 எழுத்துகள் (a-z, 0-9, . _ -)");
            }
            if (pPass == null || pPass.length() < 6) {
                throw new ApiException(400, "முதல்வர் கடவுச்சொல் குறைந்தது 6 எழுத்துகள்");
            }
            if (db.one("SELECT id FROM users WHERE username = ?", pUser) != null) {
                throw new ApiException(409, "இந்த பயனர் பெயர் ஏற்கனவே உள்ளது");
            }
        }
        final String fUser = pUser;
        final String fPass = pPass;
        final String fName = pName == null ? "முதல்வர்" : pName;
        return db.tx(() -> {
            LocalDateTime now = LocalDateTime.now(zone);
            long nid = db.insert("INSERT INTO institutions (name, kind, phone, address, weekly_off, created_at) VALUES (?,?,?,?,?,?)",
                    name, kind, phone, address, weeklyOff, now);
            LocalDate t = today();
            int startYear = t.getMonthValue() >= 6 ? t.getYear() : t.getYear() - 1;
            String yname = startYear + "-" + pad((startYear + 1) % 100, 2);
            db.insert("INSERT INTO academic_years (inst_id, name, start_date, end_date, is_current) VALUES (?,?,?,?,1)",
                    nid, yname, LocalDate.of(startYear, 6, 1), LocalDate.of(startYear + 1, 4, 30));
            if (fUser != null) {
                String salt = randomHex(16);
                db.insert("INSERT INTO users (username, pass_salt, pass_hash, display_name, user_type, inst_id, active, created_at) VALUES (?,?,?,?,?,?,1,?)",
                        fUser, salt, hash(fPass, salt), fName, "principal", nid, now);
            }
            return U.map("id", nid);
        });
    }

    public void deleteInstitution(Ctx c, long id) {
        c.needAdmin();
        mustOne("நிறுவனம்", "SELECT id FROM institutions WHERE id = ?", id);
        db.tx(() -> {
            db.update("DELETE FROM auth_tokens WHERE inst_id = ?", id);
            db.update("DELETE FROM institutions WHERE id = ?", id);
            return null;
        });
    }

    /** Admin picks the institution he is working in. */
    public void open(Ctx c, long id) {
        c.needAdmin();
        mustOne("நிறுவனம்", "SELECT id FROM institutions WHERE id = ?", id);
        db.update("UPDATE auth_tokens SET inst_id = ? WHERE token = ?", id, c.token());
    }
}
