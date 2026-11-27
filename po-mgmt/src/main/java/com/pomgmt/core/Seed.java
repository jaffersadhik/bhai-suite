package com.pomgmt.core;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Demo data — runs once, only when the branches table is empty. */
public class Seed {
    private final Db db;
    private final ZoneId zone;

    public Seed(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private static final String[][] BRANCHES = {
            {"R1", "T. Nagar", "Chennai"}, {"R2", "Anna Nagar", "Chennai"}, {"R3", "Velachery", "Chennai"},
            {"R4", "Tambaram", "Chennai"}, {"R5", "RS Puram", "Coimbatore"}, {"R6", "Gandhipuram", "Coimbatore"},
            {"R7", "Town Hall", "Madurai"}, {"R8", "Central", "Trichy"}, {"R9", "Bazaar", "Salem"},
            {"R10", "Main Road", "Erode"}, {"R11", "Palai Road", "Tirunelveli"}, {"R12", "Katpadi", "Vellore"},
            {"R13", "Big Street", "Thanjavur"}, {"R14", "Avinashi Road", "Tiruppur"}, {"R15", "Kovai Road", "Karur"}};

    private static final String[][] USERS = {
            {"exec1", "Ravi (Purchase Executive)", "purchase_executive"},
            {"mgr1", "Kumar (Purchase Manager)", "purchase_manager"},
            {"appr1", "Suresh (Approver)", "approver"},
            {"mgmt1", "Management", "management"}};

    public void run() {
        if (db.one("SELECT id FROM branches LIMIT 1") != null) return;

        for (String[] b : BRANCHES)
            db.insert("INSERT INTO branches (code, name, address, active) VALUES (?, ?, ?, 1)", b[0], b[1], b[2]);
        // demo users belong to the bootstrap admin's hierarchy so the admin sees them
        Map<String, Object> adm = db.one("SELECT id FROM users WHERE username = 'admin'");
        Long adminId = adm == null ? null : U.lng(adm.get("id"));
        for (String[] u : USERS) {
            String salt = U.salt();
            db.insert("INSERT INTO users (username, full_name, role, pass_salt, pass_hash, active, created_by, created_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                    u[0], u[1], u[2], salt, U.sha256(salt + "demo1234"), adminId, now());
        }
        long abc = db.insert("INSERT INTO suppliers (name, gstin, address, contact_person, mobile, payment_terms, credit_days, created_at) "
                + "VALUES ('ABC Garments','33ABCDE1234F1Z5','Tiruppur','Mr. Anand','9840011111','30 days credit',30,?)", now());
        db.insert("INSERT INTO agencies (supplier_id, name, location) VALUES (?, 'ABC Tiruppur Agency', 'Tiruppur')", abc);
        db.insert("INSERT INTO agencies (supplier_id, name, location) VALUES (?, 'ABC Chennai Agency', 'Chennai')", abc);
        long xyz = db.insert("INSERT INTO suppliers (name, gstin, address, contact_person, mobile, payment_terms, credit_days, created_at) "
                + "VALUES ('XYZ Textiles','33XYZAB5678K1Z2','Erode','Ms. Priya','9840022222','15 days credit',15,?)", now());
        db.insert("INSERT INTO agencies (supplier_id, name, location) VALUES (?, 'XYZ Erode Agency', 'Erode')", xyz);
        db.insert("INSERT INTO suppliers (name, gstin, address, contact_person, mobile, payment_terms, credit_days, created_at) "
                + "VALUES ('PQR Apparels','33PQRST9012L1Z8','Chennai','Mr. Bala','9840033333','Advance',0,?)", now());

        long shirt = item("SHIRT-001", "Formal Shirt", "Shirts", "Allen", "White", "6205", 5, List.of("S", "M", "L", "XL", "XXL"));
        item("TSHIRT-001", "Round Neck T-Shirt", "T-Shirts", "Ruf&Tuf", "Black", "6109", 5, List.of("S", "M", "L", "XL", "XXL"));
        item("JEANS-001", "Slim Fit Jeans", "Jeans", "Spykar", "Blue", "6203", 12, List.of("30", "32", "34", "36", "38"));
        item("KURTA-001", "Cotton Kurta", "Ethnic", "Manyavar", "Cream", "6211", 5, List.of("S", "M", "L", "XL"));

        budget("2026-Q4", "R1", "SHIRT-001", 500);
        budget("2026-Q4", "R2", "SHIRT-001", 300);
        budget("2026-Q4", "R3", "SHIRT-001", 200);
        budget("2026-Q4", "R4", "SHIRT-001", 150);
        budget("2026-Q4", "R5", "SHIRT-001", 300);
        budget("2026-Q4", "R1", "TSHIRT-001", 400);
        budget("2026-Q4", "R1", "JEANS-001", 200);

        // one sample purchase entry PE-0001 -> two branch POs (so reports/dashboard have data)
        long eid = db.insert("INSERT INTO purchase_entries (common_ref, supplier_id, agency_id, period, order_book_ref, ordered_by, remarks, delivery_date, status, created_at) "
                + "VALUES ('PE-0001', ?, (SELECT id FROM agencies WHERE name='ABC Tiruppur Agency'), '2026-Q4', 'OB-1001', 'Ravi (Purchase Executive)', 'Season stock', '2026-12-10', 'approved', ?)",
                abc, now());
        int[][] sizes = {{20, 200}, {30, 200}, {25, 200}, {15, 200}, {10, 200}};   // S,M,L,XL,XXL qty/rate
        String[] sl = {"S", "M", "L", "XL", "XXL"};
        String[][] pos = {{"R1", "10001/R1", "60"}, {"R2", "10002/R2", "0"}};
        for (String[] po : pos) {
            Map<String, Object> br = db.one("SELECT id FROM branches WHERE code = ?", po[0]);
            int sub = 0;
            for (int[] s : sizes) sub += s[0] * s[1];
            long pid = db.insert("INSERT INTO purchase_orders (po_number, entry_id, branch_id, status, ordered_by, approved_by, approved_at, subtotal, created_at) "
                    + "VALUES (?, ?, ?, 'approved', 'Ravi (Purchase Executive)', 'Suresh (Approver)', ?, ?, ?)",
                    po[1], eid, U.lng(br.get("id")), now(), sub, now());
            for (int i = 0; i < sl.length; i++) {
                int recv = (po[0].equals("R1") && sl[i].equals("S")) ? Integer.parseInt(po[2]) : 0;
                recv = Math.min(recv, sizes[i][0]);
                db.insert("INSERT INTO po_lines (po_id, item_id, size, qty, rate, amount, received_qty) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        pid, shirt, sl[i], sizes[i][0], sizes[i][1], sizes[i][0] * sizes[i][1], recv);
            }
        }
        db.update("INSERT INTO counters (name, val) VALUES ('po', 10002)");
        db.update("INSERT INTO counters (name, val) VALUES ('entry', 1)");
    }

    private long item(String code, String desc, String cat, String brand, String colour, String hsn, double gst, List<String> sizes) {
        long iid = db.insert("INSERT INTO items (item_code, barcode, category, brand, description, colour, hsn, gst_pct, active) "
                + "VALUES (?, '', ?, ?, ?, ?, ?, ?, 1)", code, cat, brand, desc, colour, hsn, gst);
        int i = 0;
        for (String s : sizes) db.insert("INSERT INTO item_sizes (item_id, size, sort_order) VALUES (?, ?, ?)", iid, s, i++);
        return iid;
    }

    private void budget(String period, String bcode, String icode, int qty) {
        Map<String, Object> b = db.one("SELECT id FROM branches WHERE code = ?", bcode);
        Map<String, Object> it = db.one("SELECT id FROM items WHERE item_code = ?", icode);
        db.insert("INSERT INTO budgets (period, branch_id, item_id, size, budget_qty, prepared_by, approved_by, status, revision, created_at) "
                + "VALUES (?, ?, ?, '', ?, 'mgr1', 'appr1', 'approved', 1, ?)", period, U.lng(b.get("id")), U.lng(it.get("id")), qty, now());
    }
}
