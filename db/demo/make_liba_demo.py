#!/usr/bin/env python3
"""
Generates db/demo/liba-demo.sql - demo data for "Liba Fashion Readymade Showroom - Ladies Special".

  python db/demo/make_liba_demo.py            -> writes db/demo/liba-demo.sql

The SQL file:
  * deletes an earlier Liba demo (same fixed ids / usernames), so it can be re-run to reset the demo
  * creates the company, 2 shop branches + the Thiruvanmiyur warehouse, payment modes, 6 users, ladies-wear groups / items / sizes / barcodes
  * creates ~60 days of bills ending today (dates are relative to the day you import it)
  * sets branch-wise stock (received stock minus what the bills sold; a few lines are negative on purpose)
  * warehouse: 3 suppliers, 5 purchase orders delivered to the warehouse (received / partly received / ordered),
    8 stock transfers warehouse -> shops (received, two with a shortage, two still in transit) and warehouse stock

Run the NEW app version once before importing (it adds the branch / payment / stock / cost / profit columns).
"""
import base64, hashlib, os, random
from demo_photos import photo_sql, demo_orders_sql
from demo_b2b import b2b_sql

random.seed(20261102)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "liba-demo.sql")
DEMO_PASSWORD = "admin1234"
DAYS = 60

CID = "clibademo01"
CO = dict(name="Liba Fashion Readymade Showroom", tagline="Ladies Special · லேடீஸ் ஸ்பெஷல்", owner="Liba Owner",
          phone="9876500001", address="East Coast Road, Palavakkam", city="Chennai", prefix="LIBA",
          footer="நன்றி! மீண்டும் வருக ❤️ · Liba Fashion")
BR = [("blibapal01", "பாலவாக்கம் (Palavakkam)"), ("blibacgl01", "செங்கல்பட்டு (Chengalpattu)")]
WH = ("wlibatvm01", "திருவான்மியூர் Warehouse (Thiruvanmiyur)")   # all purchases land here, then go to the shops
SUPPLIERS = [("slibasup01", "Surat Textiles Wholesale", "9824011111", "24AABCS1234F1Z5", "Ring Road, Surat"),
             ("slibasup02", "Tiruppur Knit Garments", "9442022222", "33AACCT5678K1Z2", "Avinashi Road, Tiruppur"),
             ("slibasup03", "Chennai Abaya House", "9840033333", "33AADCA9012M1Z8", "Mannady, Chennai")]
PAY = [("plibacash1", "Cash", "cash"), ("plibacard1", "Card", "card"),
       ("plibaupij1", "UPI - Jafar", "upi"), ("plibaupis1", "UPI - Sadik", "upi")]
# username, name, type, branch, own UPI mode (their GPay)
USERS = [("liba.owner", "Liba Owner", "owner", None, None),
         ("liba.jafar", "Jafar", "sales", BR[0][0], "plibaupij1"),
         ("liba.sadik", "Sadik", "sales", BR[1][0], "plibaupis1"),
         ("liba.stock.pal", "Nasreen (Stock)", "stock", BR[0][0], None),
         ("liba.stock.cgl", "Farhana (Stock)", "stock", BR[1][0], None),
         ("liba.wh", "Imran (Warehouse)", "warehouse", WH[0], None)]

S = {"a": ["M", "L", "XL", "XXL"], "a5": ["S", "M", "L", "XL", "XXL"], "c": ["36", "38", "40", "42", "44"],
     "bra": ["32", "34", "36", "38", "40"], "free": ["Free size"], "abaya": ["52", "54", "56", "58"]}
# group id, Tamil, English, emoji, tile colour, items [Tamil, English, size set, first rate, step]
CAT = [
    ("nighty", "நைட்டி", "Nighty", "🌙", "#e3e9ff", [
        ["காட்டன் நைட்டி", "Cotton Nighty", "a", 280, 20], ["பிரிண்டட் நைட்டி", "Printed Nighty", "a", 340, 20],
        ["ஃபீடிங் நைட்டி", "Feeding Nighty", "a", 420, 20], ["நைட் சூட் செட்", "Night Suit Set", "a", 550, 30]]),
    ("churidar", "சுடிதார் செட்", "Churidar Set", "👗", "#ffe1ea", [
        ["காட்டன் சுடிதார் செட்", "Cotton Churidar Set", "c", 650, 30], ["பிரிண்டட் சுடிதார் செட்", "Printed Churidar Set", "c", 790, 30],
        ["டிசைனர் சுடிதார் செட்", "Designer Churidar Set", "c", 1250, 50], ["அனார்கலி செட்", "Anarkali Set", "c", 1490, 50]]),
    ("kurti", "குர்த்தி", "Kurti", "👚", "#dff1d8", [
        ["காட்டன் குர்த்தி", "Cotton Kurti", "a5", 399, 20], ["ரேயான் குர்த்தி", "Rayon Kurti", "a5", 499, 20],
        ["லாங் குர்த்தி", "Long Kurti", "a5", 650, 25], ["குர்த்தி + பேண்ட் செட்", "Kurti Pant Set", "a5", 899, 30]]),
    ("saree", "சேலை", "Saree", "🥻", "#fff0c9", [
        ["காட்டன் சேலை", "Cotton Saree", "free", 690, 0], ["சிந்தெடிக் சேலை", "Synthetic Saree", "free", 450, 0],
        ["சில்க் காட்டன் சேலை", "Silk Cotton Saree", "free", 1450, 0], ["ஃபேன்சி பார்ட்டி சேலை", "Fancy Party Saree", "free", 1990, 0]]),
    ("abaya", "அபாயா / பர்தா", "Abaya / Burqa", "🧕", "#e8eef0", [
        ["பிளைன் அபாயா", "Plain Abaya", "abaya", 950, 50], ["எம்ப்ராய்டரி அபாயா", "Embroidery Abaya", "abaya", 1450, 50],
        ["ஹிஜாப் / ஸ்கார்ஃப்", "Hijab / Scarf", "free", 180, 0]]),
    ("bottom", "லெக்கிங்ஸ் & பேண்ட்", "Leggings & Pants", "👖", "#ffe7d2", [
        ["காட்டன் லெக்கிங்ஸ்", "Cotton Leggings", "a", 199, 10], ["ஆங்கிள் லெக்கிங்ஸ்", "Ankle Leggings", "a", 220, 10],
        ["பலாஸோ பேண்ட்", "Palazzo Pant", "a", 349, 20], ["பட்டியாலா பேண்ட்", "Patiala Pant", "a", 299, 10]]),
    ("inner", "இன்னர்வேர்", "Innerwear", "🩱", "#f1e4f6", [
        ["பிரா", "Bra", "bra", 160, 10], ["பேண்டீஸ் (3 பேக்)", "Panty 3-pack", "a", 199, 10],
        ["பாவாடை (Petticoat)", "Petticoat", "a", 180, 10], ["ஸ்லிப் / கேமிசோல்", "Slip / Camisole", "a", 150, 10]]),
    ("blouse", "ரெடிமேட் பிளவுஸ்", "Readymade Blouse", "🎀", "#dcefec", [
        ["காட்டன் பிளவுஸ்", "Cotton Blouse", "c", 299, 20], ["டிசைனர் பிளவுஸ்", "Designer Blouse", "c", 599, 30]]),
    ("dupatta", "துப்பட்டா", "Dupatta & Stole", "🧣", "#fde2e2", [
        ["காட்டன் துப்பட்டா", "Cotton Dupatta", "free", 150, 0], ["ஷிஃபான் துப்பட்டா", "Chiffon Dupatta", "free", 220, 0]]),
]
CUSTOMERS = ["Ayesha", "Fathima", "Meena", "Lakshmi", "Priya", "Kavitha", "Shabana", "Nasreen", "Revathi", "Anitha",
             "Divya", "Sumaiya", "Rizwana", "Selvi", "Jasmine", "Hameeda", "Deepa", "Sangeetha", "Zareena", "Mumtaz"]
DEMO_CUSTOMER = ("Ayesha", "9840054321")    # use this number to show "cancel from a bill" (பில்லிலிருந்து ரத்து)
_rr = random.Random(77)
REGULARS = [(_rr.choice(CUSTOMERS), "9" + str(_rr.randint(100000000, 999999999))) for _ in range(40)]


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return repr(v)
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def pbkdf2(pw):
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, 120000, 32)
    return base64.b64encode(salt).decode(), base64.b64encode(dk).decode()


def ean13(n12):
    s = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(n12))
    return n12 + str((10 - s % 10) % 10)


sql = []
w = sql.append
w("-- Liba Fashion Readymade Showroom (Ladies Special) - DEMO DATA. Generated by make_liba_demo.py")
w("-- Re-running this file deletes the previous Liba demo first. Logins: see README / demo guide. Password: " + DEMO_PASSWORD)
w("SET NAMES utf8mb4;")
w("SET time_zone = '+05:30';   -- the app stores India time; NOW() / CURDATE() below must be India time too")
w("START TRANSACTION;")
# ---- remove an earlier demo ----
w(f"DELETE t FROM auth_tokens t JOIN users u ON u.id = t.user_id WHERE u.company_id = '{CID}' OR u.username LIKE 'liba.%';")
w(f"DELETE FROM users WHERE company_id = '{CID}' OR username LIKE 'liba.%';")
w(f"DELETE FROM bill_lines WHERE company_id = '{CID}';")
w(f"DELETE FROM bills WHERE company_id = '{CID}';")
w(f"DELETE FROM companies WHERE id = '{CID}';")   # cascades: branches, stock, pay_modes, groups, items, sizes

# ---- company ----
w("INSERT INTO companies (id, name, tagline, owner_name, phone, address, city, gstin, email, bill_prefix, show_bismillah, bill_footer, "
  "bill_seq, catalog_version, catalog_setup, archived, created_at, updated_at) VALUES "
  f"({q(CID)}, {q(CO['name'])}, {q(CO['tagline'])}, {q(CO['owner'])}, {q(CO['phone'])}, {q(CO['address'])}, {q(CO['city'])}, '', '', "
  f"{q(CO['prefix'])}, 1, {q(CO['footer'])}, 0, 1, 1, 0, NOW() - INTERVAL {DAYS + 5} DAY, NOW());")
for i, (bid, bname) in enumerate(BR):
    w(f"INSERT INTO branches (company_id, id, name, kind, sort_order, created_at) VALUES ({q(CID)}, {q(bid)}, {q(bname)}, 'shop', {i}, NOW() - INTERVAL {DAYS + 5} DAY);")
w(f"INSERT INTO branches (company_id, id, name, kind, sort_order, created_at) VALUES ({q(CID)}, {q(WH[0])}, {q(WH[1])}, 'warehouse', 9, NOW() - INTERVAL {DAYS + 5} DAY);")
for sid, sn, sp, sg, sa in SUPPLIERS:
    w(f"INSERT INTO suppliers (company_id, id, name, phone, gstin, address, created_at) VALUES ({q(CID)}, {q(sid)}, {q(sn)}, {q(sp)}, {q(sg)}, {q(sa)}, NOW() - INTERVAL {DAYS} DAY);")
w(f"UPDATE companies SET gstin = '33AAKFL1234M1Z5' WHERE id = {q(CID)};")   # demo GSTIN (for tax invoices)
w(f"UPDATE companies SET shop_code = 'libademo', shop_enabled = 1 WHERE id = {q(CID)};")   # online shop: shop.html?s=libademo
for i, (pid, pname, kind) in enumerate(PAY):
    w(f"INSERT INTO pay_modes (company_id, id, name, kind, sort_order, active) VALUES ({q(CID)}, {q(pid)}, {q(pname)}, {q(kind)}, {i}, 1);")
for i, (un, name, typ, bid, _) in enumerate(USERS):
    salt, h = pbkdf2(DEMO_PASSWORD)
    w("INSERT INTO users (id, username, full_name, user_type, company_id, branch_id, pass_salt, pass_hash, active, must_change, created_at, updated_at) VALUES "
      f"('ulibademo{i:02d}', {q(un)}, {q(name)}, {q(typ)}, {q(CID)}, {q(bid)}, {q(salt)}, {q(h)}, 1, 0, NOW() - INTERVAL {DAYS + 5} DAY, NOW());")

# ---- catalog ----
WRATE = {}          # (item_id, size) -> wholesale price
sizes = []          # (item_id, ta, en, size, rate, popularity)
barcode_labels = []
code_no = 0
for gi, (gid, gta, gen, emo, col, items) in enumerate(CAT):
    w(f"INSERT INTO item_groups (company_id, id, name_ta, name_en, emoji, color, sort_order) VALUES ({q(CID)}, {q(gid)}, {q(gta)}, {q(gen)}, {q(emo)}, {q(col)}, {gi});")
    for ii, (ta, en, sset, base, step) in enumerate(items):
        iid = f"l_{gid}_{ii}"
        w(f"INSERT INTO items (company_id, id, group_id, name_ta, name_en, sort_order) VALUES ({q(CID)}, {q(iid)}, {q(gid)}, {q(ta)}, {q(en)}, {gi * 10 + ii});")
        w(photo_sql(CID, iid, en, emo, col))                     # sample picture for the online shop
        cost_pc = random.uniform(0.55, 0.68)
        pop = random.uniform(0.6, 1.6) * (1.6 if gid in ("nighty", "kurti", "bottom", "inner") else 1.0)
        for si, sz in enumerate(S[sset]):
            code_no += 1
            code = ean13("8906{:08d}".format(20261000 + code_no))     # demo EAN-13 barcodes
            rate = base + step * si
            # cost (அடக்க விலை) is optional: most items 55-68 % of the rate, a few left blank on purpose
            cost = None if gid in ("dupatta",) or (gid, ii) == ("saree", 3) else round(rate * cost_pc / 5) * 5
            wrate = max(int(round(rate * 0.78 / 5) * 5), int(cost or 0) + 5)       # wholesale selling price ~22 % below retail
            WRATE[(iid, sz)] = wrate
            w(f"INSERT INTO item_sizes (company_id, item_id, size_label, rate, wrate, cost, barcode, sort_order) VALUES ({q(CID)}, {q(iid)}, {q(sz)}, {rate}, {wrate}, {q(cost)}, {q(code)}, {si});")
            mid = 1.4 if sz in ("L", "XL", "38", "40", "Free size", "54", "56", "34", "36") else 0.8
            sizes.append((iid, ta, en, sz, rate, pop * mid, cost))
            barcode_labels.append((code, ta, en, sz, rate))

# ---- bills ----
weights = [s[5] for s in sizes]
stock_out = {}       # (branch, item, size) -> pieces sold
bills = []           # (days_ago, seconds, branch, user, ...)
staff = {BR[0][0]: [USERS[1], USERS[3]], BR[1][0]: [USERS[2], USERS[4]]}
for d in range(DAYS, -1, -1):
    for bi, (bid, _) in enumerate(BR):
        wk = (DAYS - d) % 7          # day of week index (relative, enough for a demo)
        base = 9 if bi == 0 else 6   # Palavakkam is the bigger shop
        n = random.randint(base - 3, base + 3) + (4 if wk in (5, 6) else 0)
        if d == 0:
            n = random.randint(5, 8) if bi == 0 else random.randint(3, 6)
        for _ in range(n):
            u = staff[bid][0] if random.random() < 0.72 else staff[bid][1]
            if d == 0:
                when = ("now", random.randint(5, 330))          # minutes before the import time
            else:
                hh = random.choices(range(10, 22), weights=[3, 5, 6, 5, 3, 3, 5, 7, 9, 10, 9, 5])[0]
                when = ("day", d, hh * 3600 + random.randint(0, 3599))
            lines, seen = [], set()
            for _ in range(random.choices([1, 2, 3, 4], weights=[45, 33, 16, 6])[0]):
                sz = random.choices(sizes, weights=weights)[0]
                if (sz[0], sz[3]) in seen:
                    continue
                seen.add((sz[0], sz[3]))
                lines.append((sz, random.choices([1, 2, 3], weights=[78, 18, 4])[0]))
            r = random.random()
            if r < 0.40:
                pay = PAY[0]
            elif r < 0.55:
                pay = PAY[1]
            else:   # UPI goes to the salesman's own GPay; the stock user's bills use either
                own = u[4] or random.choice(["plibaupij1", "plibaupis1"])
                pay = next(p for p in PAY if p[0] == own)
            r_ = random.random()
            if r_ < 0.025:                                   # the demo's regular customer: many bills over the months
                cust, phone = DEMO_CUSTOMER
            elif r_ < 0.35:                                  # other regulars come back now and then
                cust, phone = random.choice(REGULARS)
            else:
                cust = random.choice(CUSTOMERS) if random.random() < 0.65 else ""
                phone = "9" + str(random.randint(100000000, 999999999)) if random.random() < 0.7 else ""
            bills.append((when, bid, u, lines, pay, cust, phone))


def sort_key(b):
    w_ = b[0]
    return (-w_[1], w_[2]) if w_[0] == "day" else (0, -w_[1])


bills.sort(key=sort_key)
for seq, (when, bid, u, lines, pay, cust, phone) in enumerate(bills, 1):
    at = (f"TIMESTAMP(CURDATE() - INTERVAL {when[1]} DAY) + INTERVAL {when[2]} SECOND" if when[0] == "day"
          # today's bills: spread over the part of today that has passed, so they are always "today" whatever the import time
          else f"TIMESTAMP(CURDATE()) + INTERVAL FLOOR(TIMESTAMPDIFF(SECOND, TIMESTAMP(CURDATE()), NOW()) * {1 - when[1] / 400:.3f}) SECOND")
    pcs = sum(qn for _, qn in lines)
    tot = sum(s[4] * qn for s, qn in lines)
    w("INSERT INTO bills (company_id, branch_id, bill_seq, bill_no, created_at, customer_name, customer_phone, billed_by_user, billed_by_name, "
      "pieces, total, pay_mode_id, pay_name, pay_kind) VALUES "
      f"({q(CID)}, {q(bid)}, {seq}, '{CO['prefix']}-{seq:04d}', {at}, {q(cust)}, {q(phone)}, {q(u[0])}, {q(u[1])}, {pcs}, {tot}, "
      f"{q(pay[0])}, {q(pay[1])}, {q(pay[2])});")
    w("SET @b = LAST_INSERT_ID();")
    vals = []
    for ln, (s, qn) in enumerate(lines, 1):
        vals.append(f"({q(CID)}, @b, {ln}, {q(s[0])}, {q(s[1])}, {q(s[2])}, {q(s[3])}, {qn}, {s[4]}, {q(s[6])}, {s[4] * qn})")
        k = (bid, s[0], s[3])
        stock_out[k] = stock_out.get(k, 0) + qn
    w("INSERT INTO bill_lines (company_id, bill_id, line_no, item_id, item_name, item_name_en, size_label, qty, rate, cost, amount) VALUES " + ", ".join(vals) + ";")
# wholesale: retailers buying on credit, tax invoices (PREFIX-W....) and their payments
_b2b, _last = b2b_sql(CID, CO["prefix"], q, sizes, [b[0] for b in BR], {BR[0][0]: (USERS[3][0], USERS[3][1]), BR[1][0]: (USERS[4][0], USERS[4][1])},
                      len(bills), stock_out, random.Random(21), WRATE)
for _l in _b2b:
    w(_l)
w(f"UPDATE companies SET bill_seq = {_last} WHERE id = {q(CID)};")

# ---- stock: received - sold (a few negative / zero / low on purpose) ----
stock_rows = []
for bi, (bid, _) in enumerate(BR):
    by = USERS[3][1] if bi == 0 else USERS[4][1]
    for s in sizes:
        sold = stock_out.get((bid, s[0], s[3]), 0)
        r = random.random()
        if r < 0.05:
            recv = max(0, sold - random.randint(1, 3))          # negative: sold more than recorded
        elif r < 0.12:
            recv = sold                                          # zero
        elif r < 0.25:
            recv = sold + random.randint(1, 3)                   # low
        else:
            recv = sold + random.randint(4, 18)
        qty = recv - sold
        stock_rows.append(f"({q(CID)}, {q(bid)}, {q(s[0])}, {q(s[3])}, {qty}, NOW() - INTERVAL {random.randint(1, 600)} MINUTE, {q(by)})")
for i in range(0, len(stock_rows), 50):
    w("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES " + ", ".join(stock_rows[i:i + 50]) + ";")

# ---- warehouse: purchase orders received at Thiruvanmiyur, transfers to the shops ----
wh_stock = {}
pool = sizes[:]
random.shuffle(pool)
# (supplier, days ago, status, lines, paid fraction, recv fraction)
po_plan = [(0, 40, "received", pool[0:14], 1.0, 1.0), (1, 25, "received", pool[14:30], 0.5, 1.0),
           (2, 12, "received", pool[30:40], 1.0, 1.0), (0, 4, "partial", pool[40:52], 0.0, 0.6),
           (1, 1, "ordered", pool[52:62], 0.0, 0.0)]
GST = 5
for n, (si, ago, status, lines, paidf, recvf) in enumerate(po_plan, 1):
    sid, sname = SUPPLIERS[si][0], SUPPLIERS[si][1]
    rows, sub, gst = [], 0, 0
    for ln, s_ in enumerate(lines, 1):
        qty = random.choice([10, 12, 15, 20, 24, 30])
        rq = qty if recvf >= 1 else (int(qty * recvf) if ln % 2 else 0) if recvf > 0 else 0
        uc = s_[6] if s_[6] is not None else round(s_[4] * 0.6)
        amt = round(qty * uc, 2); sub += amt; gst += round(amt * GST / 100, 2)
        rows.append(f"(@po, {q(CID)}, {ln}, {q(s_[0])}, {q(s_[1])}, {q(s_[3])}, {qty}, {rq}, {uc}, {GST}, {amt})")
        if rq:
            wh_stock[(s_[0], s_[3])] = wh_stock.get((s_[0], s_[3]), 0) + rq
    other = 250 if n % 2 else 0
    total = round(sub + gst + other, 2)
    paid = round(total * paidf, 2)
    recv = f"NOW() - INTERVAL {max(ago - 2, 0)} DAY" if status != "ordered" else "NULL"
    w("INSERT INTO purchase_orders (company_id, branch_id, po_seq, po_no, supplier_id, supplier_name, order_date, expected_date, supplier_ref, notes, status, "
      "subtotal, gst_total, other_charges, discount, total, paid, created_at, created_by_user, created_by_name, received_at, received_by, updated_at) VALUES "
      f"({q(CID)}, {q(WH[0])}, {n}, 'PO-{n:04d}', {q(sid)}, {q(sname)}, CURDATE() - INTERVAL {ago} DAY, CURDATE() - INTERVAL {max(ago - 3, -3)} DAY, 'INV-{7100 + n * 13}', "
      f"{q('Warehouse-க்கு டெலிவரி · எண்ணி சரிபார்த்து பெறவும்')}, {q(status)}, {sub}, {gst}, {other}, 0, {total}, {paid}, NOW() - INTERVAL {ago} DAY, 'liba.wh', 'Imran (Warehouse)', "
      f"{recv}, {q('Imran (Warehouse)' if status != 'ordered' else '')}, NOW() - INTERVAL {max(ago - 2, 0)} DAY);")
    w("SET @po = LAST_INSERT_ID();")
    _pd = paid
    if _pd > 0:                                            # supplier payments with dates (advance, then the rest)
        _parts = [_pd] if _pd < 20000 else [round(_pd * 0.4, 2), round(_pd - round(_pd * 0.4, 2), 2)]
        for _i, _a in enumerate(_parts):
            w(f"INSERT INTO po_payments (company_id, po_id, amount, paid_on, mode, ref, created_at, by_name) VALUES ({q(CID)}, @po, {_a}, "
              f"CURDATE() - INTERVAL {max(ago - _i * 7, 0)} DAY, {q(['Bank (NEFT)', 'UPI', 'Cheque'][_i % 3])}, {q('Ref ' + str(5100 + n * 10 + _i))}, NOW(), 'Demo');")
    w("INSERT INTO po_lines (po_id, company_id, line_no, item_id, item_name, size_label, qty, received_qty, unit_cost, gst_pct, amount) VALUES " + ", ".join(rows) + ";")
w(f"UPDATE companies SET po_seq = {len(po_plan)} WHERE id = {q(CID)};")

# transfers from the warehouse: (to branch idx, days ago, status, share of warehouse lines, shortage?)
tr_plan = [(0, 36, "received", 0, False), (1, 35, "received", 1, False), (0, 22, "received", 2, True),
           (1, 21, "received", 3, False), (0, 10, "received", 4, False), (1, 9, "received", 5, True),
           (0, 1, "sent", 6, False), (1, 0, "sent", 7, False)]
keys = sorted(wh_stock)
for n, (bi, ago, status, part, short) in enumerate(tr_plan, 1):
    chunk = keys[part::8][:9]
    rows = []
    for ln, k in enumerate(chunk, 1):
        have = wh_stock[k]
        qty = min(have - 2, random.choice([3, 4, 5, 6]))
        if qty < 1:
            continue
        rq = qty if status == "received" else 0
        if short and ln == 2:
            rq = qty - 1                                # one piece short on arrival
        wh_stock[k] -= qty
        nm = next(s_[1] for s_ in sizes if s_[0] == k[0] and s_[3] == k[1])
        rows.append(f"(@tr, {q(CID)}, {len(rows) + 1}, {q(k[0])}, {q(nm)}, {q(k[1])}, {qty}, {rq})")
    if not rows:
        continue
    rec = f"NOW() - INTERVAL {max(ago - 1, 0)} DAY" if status == "received" else "NULL"
    rby = (USERS[3][1] if bi == 0 else USERS[4][1]) if status == "received" else ""
    w("INSERT INTO stock_transfers (company_id, tr_seq, tr_no, from_branch, to_branch, status, notes, created_at, created_by_user, created_by_name, received_at, received_by) VALUES "
      f"({q(CID)}, {n}, 'TR-{n:04d}', {q(WH[0])}, {q(BR[bi][0])}, {q(status)}, {q('வண்டி TN-09-AB-' + str(1200 + n))}, NOW() - INTERVAL {ago} DAY - INTERVAL 3 HOUR, 'liba.wh', 'Imran (Warehouse)', {rec}, {q(rby)});")
    w("SET @tr = LAST_INSERT_ID();")
    w("INSERT INTO transfer_lines (tr_id, company_id, line_no, item_id, item_name, size_label, qty, received_qty) VALUES " + ", ".join(rows) + ";")
w(f"UPDATE companies SET tr_seq = {len(tr_plan)} WHERE id = {q(CID)};")
wh_rows = [f"({q(CID)}, {q(WH[0])}, {q(k[0])}, {q(k[1])}, {v}, NOW() - INTERVAL {random.randint(5, 900)} MINUTE, 'Imran (Warehouse)')" for k, v in sorted(wh_stock.items())]
for i in range(0, len(wh_rows), 50):
    w("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES " + ", ".join(wh_rows[i:i + 50]) + ";")
for line in demo_orders_sql(CID, sizes, BR, REGULARS + [DEMO_CUSTOMER], random.Random(5)):     # online-shop orders in every state
    w(line)
w("COMMIT;")


with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(sql) + "\n")
with open(os.path.join(os.path.dirname(OUT), "liba-barcodes.tsv"), "w", encoding="utf-8") as f:
    f.write("barcode\titem\titem_en\tsize\trate\n")
    for c in barcode_labels:
        f.write("\t".join(str(x) for x in c) + "\n")
print(f"{OUT}: {len(bills)} bills, {len(sizes)} item sizes, {len(stock_rows)} shop stock rows, {len(po_plan)} POs, {len(tr_plan)} transfers, {len(wh_rows)} warehouse stock rows")
