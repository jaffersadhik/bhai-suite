#!/usr/bin/env python3
"""
Generates db/demo/rasi-demo.sql - demo data for "Rasi" (fancy, gold covering, stationery, toys, gifts, perfumes),
Kolathur, Chennai: 2 shops + 1 warehouse.

  python db/demo/make_rasi_demo.py            -> writes db/demo/rasi-demo.sql (+ rasi-barcodes.tsv)

The SQL file:
  * deletes an earlier Rasi demo (same fixed ids / usernames), so it can be re-run to reset the demo
  * creates the company, shops முகாம்பிகை தெரு + ஸ்கூல் அருகில், the Kolathur warehouse, payment modes and 6 users
  * catalog: 6 groups / 34 items with sizes (ml, inch, S/M/L ...) and rates; only packed goods (perfumes, toys, some
    stationery) carry a barcode - most billing here is "pick group -> item -> size -> rate" or a custom item
  * ~45 days of bills ending today; about half of the lines are custom items (key chain, greeting card, return gifts ...)
  * shop stock, warehouse stock, 3 suppliers, 4 purchase orders at the warehouse, 6 transfers warehouse -> shops
    (one in transit to each shop) and a few cancel bills (separate documents; the original bills stay)

Run the NEW app version once before importing (it adds the branch / warehouse / invoice / cancel tables and columns).
"""
import base64, hashlib, json, os, random
from demo_photos import photo_sql, demo_orders_sql

random.seed(20261103)
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "rasi-demo.sql")
DEMO_PASSWORD = "admin1234"
DAYS = 45

CID = "crasidemo01"
CO = dict(name="Rasi", tagline="Fancy · Covering · Stationery · Toys · Gifts · Perfumes", owner="Rasi Owner",
          phone="9876500002", address="Mugambigai Street, Kolathur", city="Chennai", prefix="RASI",
          footer="நன்றி! மீண்டும் வருக ❤️ · Rasi, Kolathur")
BR = [("brasimug01", "முகாம்பிகை தெரு (Mugambigai St)"), ("brasisch01", "ஸ்கூல் அருகில் (Near School)")]
WH = ("wrasiwh01", "கொளத்தூர் Warehouse (Kolathur)")
PAY = [("prasicash1", "Cash", "cash"), ("prasiupi01", "UPI - Rasi", "upi"), ("prasicard1", "Card", "card")]
# username, name, type, branch
USERS = [("rasi.owner", "Rasi Owner", "owner", None),
         ("rasi.mug", "Karthik", "sales", BR[0][0]),
         ("rasi.sch", "Divya", "sales", BR[1][0]),
         ("rasi.stock.mug", "Meena (Stock)", "stock", BR[0][0]),
         ("rasi.stock.sch", "Saranya (Stock)", "stock", BR[1][0]),
         ("rasi.wh", "Murugan (Warehouse)", "warehouse", WH[0])]
SUPPLIERS = [("srasisup01", "Sowcarpet Fancy Wholesale", "9840111222", "33AAKFS1234L1Z5", "Mint Street, Sowcarpet, Chennai"),
             ("srasisup02", "Parrys Stationery & Toys", "9444222333", "33AAGCP5678Q1Z1", "NSC Bose Road, Parrys, Chennai"),
             ("srasisup03", "Al-Noor Perfumes & Attar", "9789333444", "33AAOFA9012R1Z7", "Triplicane High Road, Chennai")]

S = {"free": ["Free"], "sml": ["Small", "Medium", "Big"], "bangle": ["2.2", "2.4", "2.6", "2.8"],
     "chain": ['18"', '24"', '30"'], "book": ["Small", "Long", "A4"], "col": ["12", "24"],
     "frame": ["4x6", "5x7", "8x10"], "spray": ["150ml", "200ml"], "scent": ["30ml", "50ml", "100ml"],
     "attar": ["3ml", "6ml", "12ml"], "bag": ["Small", "Medium", "Big"]}
# group id, Tamil, English, emoji, colour, barcoded?, items [Tamil, English, size set, first rate, step]
CAT = [
    ("fancy", "ஃபேன்சி", "Fancy", "💍", "#ffe1ea", False, [
        ["ஹேர் கிளிப்", "Hair Clip", "sml", 20, 15], ["ஹேர் பேண்ட்", "Hair Band", "free", 30, 0],
        ["ஃபேன்சி வளையல் செட்", "Fancy Bangles Set", "bangle", 80, 10], ["ஃபேன்சி தோடு", "Fancy Earrings", "free", 60, 0],
        ["பொட்டு பாக்கெட்", "Bindi Pack", "free", 20, 0], ["ஃபேன்சி கொலுசு", "Fancy Anklet", "free", 120, 0]]),
    ("covering", "கவரிங் நகை", "Gold Covering", "📿", "#fff0c9", False, [
        ["கவரிங் செயின்", "Covering Chain", "chain", 250, 100], ["கவரிங் வளையல்", "Covering Bangles", "bangle", 350, 25],
        ["கவரிங் ஜிமிக்கி", "Covering Jhumka", "free", 180, 0], ["கவரிங் நெக்லஸ் செட்", "Covering Necklace Set", "free", 650, 0],
        ["கவரிங் மோதிரம்", "Covering Ring", "free", 90, 0], ["கவரிங் கொலுசு", "Covering Anklet", "free", 450, 0]]),
    ("stationery", "ஸ்டேஷனரி", "Stationery", "✏️", "#dff1d8", True, [
        ["நோட்டு புத்தகம்", "Notebook", "book", 30, 15], ["பேனா பாக்ஸ் (10)", "Pen Box (10)", "free", 50, 0],
        ["பென்சில் பாக்ஸ் (10)", "Pencil Box (10)", "free", 40, 0], ["ஜியோமெட்ரி பாக்ஸ்", "Geometry Box", "free", 120, 0],
        ["கலர் பென்சில்", "Colour Pencils", "col", 60, 60], ["கிஃப்ட் ராப்பர்", "Gift Wrap Paper", "free", 15, 0],
        ["ஸ்கூல் பேக்", "School Bag", "bag", 450, 150]]),
    ("toys", "பொம்மைகள்", "Toys", "🧸", "#e3e9ff", True, [
        ["டெடி பியர்", "Teddy Bear", "sml", 199, 200], ["டாய் கார்", "Toy Car", "free", 150, 0],
        ["பார்பி டால்", "Doll", "free", 299, 0], ["ஜிக்சா புதிர்", "Jigsaw Puzzle", "free", 120, 0],
        ["ரிமோட் கார்", "Remote Car", "free", 799, 0], ["பலூன் பாக்கெட்", "Balloon Pack", "free", 50, 0]]),
    ("gifts", "கிஃப்ட் ஐட்டம்", "Gifts", "🎁", "#f1e4f6", False, [
        ["போட்டோ ஃபிரேம்", "Photo Frame", "frame", 150, 100], ["ஷோ பீஸ்", "Show Piece", "sml", 199, 150],
        ["காபி மக்", "Coffee Mug", "free", 149, 0], ["கிஃப்ட் ஹேம்பர்", "Gift Hamper", "sml", 299, 300],
        ["சுவர் கடிகாரம்", "Wall Clock", "free", 399, 0]]),
    ("perfume", "சென்ட் & அத்தர்", "Perfumes & Attar", "🌸", "#dcefec", True, [
        ["பாடி ஸ்ப்ரே", "Body Spray", "spray", 199, 50], ["சென்ட் (Perfume)", "Perfume", "scent", 250, 200],
        ["அத்தர்", "Attar", "attar", 80, 70], ["ரோல்-ஆன் டியோ", "Roll-on Deo", "free", 180, 0]]),
]
# what the counter usually types in as a custom item: (name, group, size, rate choices)
CUSTOM = [("கீ செயின்", "Key Chain", "fancy", "", [30, 40, 50]), ("கிரீட்டிங் கார்டு", "Greeting Card", "gifts", "", [20, 30, 50]),
          ("ரிட்டர்ன் கிஃப்ட்", "Return Gift", "gifts", "", [15, 20, 25, 30]), ("மெஹந்தி கோன்", "Mehndi Cone", "fancy", "", [20, 25]),
          ("நெயில் பாலிஷ்", "Nail Polish", "fancy", "", [30, 40, 60]), ("லிப்ஸ்டிக்", "Lipstick", "fancy", "", [60, 99, 149]),
          ("கிஃப்ட் பாக்ஸ்", "Gift Box", "gifts", "Medium", [40, 60, 80]), ("பிறந்தநாள் மெழுகுவர்த்தி", "Birthday Candle", "gifts", "", [20, 30]),
          ("ஸ்கெட்ச் பென்", "Sketch Pens", "stationery", "12", [40, 60]), ("இரேசர் / ஷார்ப்னர்", "Eraser / Sharpener", "stationery", "", [5, 10]),
          ("குழந்தை வாட்ச்", "Kids Watch", "toys", "", [99, 149]), ("ஹேர் கிளிப் (லூஸ்)", "Hair Clip loose", "fancy", "", [10, 15, 20]),
          ("கவரிங் கம்மல் (லூஸ்)", "Covering Studs", "covering", "", [60, 80, 120]), ("அத்தர் (லூஸ்)", "Attar loose", "perfume", "2ml", [40, 50])]
CUSTOMERS = ["Priya", "Lakshmi", "Kavya", "Anitha", "Selvi", "Revathi", "Fathima", "Deepa", "Sangeetha", "Malathi",
             "Ramesh", "Suresh", "Karthik", "Vignesh", "Ayesha", "Bhuvana", "Gayathri", "Nithya", "Yamini", "Abdul"]
DEMO_CUSTOMER = ("Priya", "9840012345")    # use this number to show "cancel from a bill" (பில்லிலிருந்து ரத்து)
_rr = random.Random(77)
REGULARS = [(_rr.choice(CUSTOMERS), "9" + str(_rr.randint(100000000, 999999999))) for _ in range(40)]

sql = []
w = sql.append


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


w("-- Rasi demo data - generated by make_rasi_demo.py. Re-import to reset the demo.")
w("SET NAMES utf8mb4;")
w("SET time_zone = '+05:30';   -- the app stores India time; NOW() / CURDATE() below must be India time too")
w("START TRANSACTION;")
w(f"DELETE t FROM auth_tokens t JOIN users u ON u.id = t.user_id WHERE u.company_id = '{CID}' OR u.username LIKE 'rasi.%';")
w(f"DELETE FROM users WHERE company_id = '{CID}' OR username LIKE 'rasi.%';")
w(f"DELETE FROM bill_lines WHERE company_id = '{CID}';")
w(f"DELETE FROM bills WHERE company_id = '{CID}';")
w(f"DELETE FROM companies WHERE id = '{CID}';")   # cascades: branches, stock, groups, items, POs, transfers, invoices ...

w("INSERT INTO companies (id, name, tagline, owner_name, phone, address, city, gstin, email, bill_prefix, show_bismillah, bill_footer, "
  "bill_seq, catalog_version, catalog_setup, archived, bill_fee, created_at, updated_at) VALUES "
  f"({q(CID)}, {q(CO['name'])}, {q(CO['tagline'])}, {q(CO['owner'])}, {q(CO['phone'])}, {q(CO['address'])}, {q(CO['city'])}, '', '', "
  f"{q(CO['prefix'])}, 0, {q(CO['footer'])}, 0, 1, 1, 0, 0.25, NOW() - INTERVAL {DAYS + 5} DAY, NOW());")
for i, (bid, bname) in enumerate(BR):
    w(f"INSERT INTO branches (company_id, id, name, kind, sort_order, created_at) VALUES ({q(CID)}, {q(bid)}, {q(bname)}, 'shop', {i}, NOW() - INTERVAL {DAYS + 5} DAY);")
w(f"INSERT INTO branches (company_id, id, name, kind, sort_order, created_at) VALUES ({q(CID)}, {q(WH[0])}, {q(WH[1])}, 'warehouse', 9, NOW() - INTERVAL {DAYS + 5} DAY);")
w(f"UPDATE companies SET shop_code = 'rasidemo', shop_enabled = 1 WHERE id = {q(CID)};")   # online shop: shop.html?s=rasidemo
for i, (pid, pname, kind) in enumerate(PAY):
    w(f"INSERT INTO pay_modes (company_id, id, name, kind, sort_order, active) VALUES ({q(CID)}, {q(pid)}, {q(pname)}, {q(kind)}, {i}, 1);")
for i, (un, name, typ, bid) in enumerate(USERS):
    salt, h = pbkdf2(DEMO_PASSWORD)
    w("INSERT INTO users (id, username, full_name, user_type, company_id, branch_id, pass_salt, pass_hash, active, must_change, created_at, updated_at) VALUES "
      f"('urasidemo{i:02d}', {q(un)}, {q(name)}, {q(typ)}, {q(CID)}, {q(bid)}, {q(salt)}, {q(h)}, 1, 0, NOW() - INTERVAL {DAYS + 5} DAY, NOW());")
for sid, sn, sp, sg, sa in SUPPLIERS:
    w(f"INSERT INTO suppliers (company_id, id, name, phone, gstin, address, created_at) VALUES ({q(CID)}, {q(sid)}, {q(sn)}, {q(sp)}, {q(sg)}, {q(sa)}, NOW() - INTERVAL {DAYS} DAY);")

# ---- catalog ----
sizes = []      # (item_id, ta, en, size, rate, popularity, cost, group)
labels = []
code_no = 0
for gi, (gid, gta, gen, emo, col, coded, items) in enumerate(CAT):
    w(f"INSERT INTO item_groups (company_id, id, name_ta, name_en, emoji, color, sort_order) VALUES ({q(CID)}, {q(gid)}, {q(gta)}, {q(gen)}, {q(emo)}, {q(col)}, {gi});")
    for ii, (ta, en, sset, base, step) in enumerate(items):
        iid = f"r_{gid}_{ii}"
        w(f"INSERT INTO items (company_id, id, group_id, name_ta, name_en, sort_order) VALUES ({q(CID)}, {q(iid)}, {q(gid)}, {q(ta)}, {q(en)}, {gi * 10 + ii});")
        w(photo_sql(CID, iid, en, emo, col))                     # sample picture for the online shop
        margin = random.uniform(0.45, 0.62) if gid in ("fancy", "covering", "gifts") else random.uniform(0.62, 0.78)
        pop = random.uniform(0.6, 1.6) * (1.8 if gid in ("fancy", "stationery", "perfume") else 1.0)
        for si, sz in enumerate(S[sset]):
            rate = base + step * si
            code = None
            if coded and not (gid == "stationery" and ii in (5,)):       # packed goods carry a barcode; loose fancy / covering / gifts don't
                code_no += 1
                code = ean13("8907{:08d}".format(31100000 + code_no))
                labels.append((code, ta, en, sz, rate))
            cost = round(rate * margin) if rate >= 50 else round(rate * margin, 1)
            w(f"INSERT INTO item_sizes (company_id, item_id, size_label, rate, cost, barcode, sort_order) VALUES ({q(CID)}, {q(iid)}, {q(sz)}, {rate}, {q(cost)}, {q(code)}, {si});")
            mid = 1.3 if si == 0 else 1.0 if si == 1 else 0.6
            sizes.append((iid, ta, en, sz, rate, pop * mid, cost, gid))

# ---- bills: mostly picked by hand, many custom lines ----
weights = [s[5] for s in sizes]
stock_out = {}
bills = []
staff = {BR[0][0]: [USERS[1], USERS[3]], BR[1][0]: [USERS[2], USERS[4]]}
for d in range(DAYS, -1, -1):
    for bi, (bid, _) in enumerate(BR):
        wk = (DAYS - d) % 7
        base = 14 if bi == 0 else 11          # school-side shop is busy in the evenings / school season
        n = random.randint(base - 4, base + 4) + (6 if wk in (5, 6) else 0)
        if d == 0:
            n = random.randint(8, 12) if bi == 0 else random.randint(6, 10)
        for _ in range(n):
            u = staff[bid][0] if random.random() < 0.75 else staff[bid][1]
            if d == 0:
                when = ("now", random.randint(5, 300))
            else:
                hours = range(9, 22)
                wts = [3, 5, 6, 5, 4, 3, 5, 9, 10, 10, 9, 7, 4] if bi == 0 else [2, 3, 4, 4, 3, 5, 9, 10, 9, 8, 7, 5, 3]
                hh = random.choices(hours, weights=wts)[0]
                when = ("day", d, hh * 3600 + random.randint(0, 3599))
            lines, seen = [], set()
            for _ in range(random.choices([1, 2, 3, 4, 5], weights=[30, 30, 22, 12, 6])[0]):
                if random.random() < 0.48:          # custom item typed at the counter
                    ta, en, g, sz, rates = random.choice(CUSTOM)
                    key = ("c", ta, sz)
                    if key in seen:
                        continue
                    seen.add(key)
                    lines.append(((None, ta, en, sz, random.choice(rates), 0, None, g), random.choices([1, 2, 3, 5, 10], weights=[60, 20, 8, 7, 5])[0]))
                else:
                    s = random.choices(sizes, weights=weights)[0]
                    if (s[0], s[3]) in seen:
                        continue
                    seen.add((s[0], s[3]))
                    qty = random.choices([1, 2, 3, 6], weights=[72, 18, 6, 4])[0] if s[4] < 100 else random.choices([1, 2], weights=[90, 10])[0]
                    lines.append((s, qty))
            if not lines:
                continue
            pay = PAY[0] if random.random() < 0.55 else PAY[1] if random.random() < 0.88 else PAY[2]
            r_ = random.random()
            if r_ < 0.025:                                   # the demo's regular customer: many bills over the months
                cust, phone = DEMO_CUSTOMER
            elif r_ < 0.35:                                  # other regulars come back now and then
                cust, phone = random.choice(REGULARS)
            else:
                cust = random.choice(CUSTOMERS) if random.random() < 0.4 else ""
                phone = "9" + str(random.randint(100000000, 999999999)) if random.random() < 0.45 else ""
            bills.append((when, bid, u, lines, pay, cust, phone))


def sort_key(b):
    w_ = b[0]
    return (-w_[1], w_[2]) if w_[0] == "day" else (0, -w_[1])


bills.sort(key=sort_key)
cancelled = []
# the demo customer's latest bill gets a cancel bill that is NOT yet exchanged - use it to show 🔁 Exchange on a new bill
last_demo = max((i for i, b in enumerate(bills) if b[6] == DEMO_CUSTOMER[1]), default=-1)
seq = 0
for idx, (when, bid, u, lines, pay, cust, phone) in enumerate(bills):
    at = (f"TIMESTAMP(CURDATE() - INTERVAL {when[1]} DAY) + INTERVAL {when[2]} SECOND" if when[0] == "day"
          # today's bills: spread over the part of today that has passed, so they are always "today" whatever the import time
          else f"TIMESTAMP(CURDATE()) + INTERVAL FLOOR(TIMESTAMPDIFF(SECOND, TIMESTAMP(CURDATE()), NOW()) * {1 - when[1] / 400:.3f}) SECOND")
    seq += 1
    no = f"{CO['prefix']}-{seq:04d}"
    pcs = sum(qn for _, qn in lines)
    tot = sum(s[4] * qn for s, qn in lines)
    w("INSERT INTO bills (company_id, branch_id, bill_seq, bill_no, created_at, customer_name, customer_phone, billed_by_user, billed_by_name, "
      "pieces, total, pay_mode_id, pay_name, pay_kind, platform_fee) VALUES "
      f"({q(CID)}, {q(bid)}, {seq}, {q(no)}, {at}, {q(cust)}, {q(phone)}, {q(u[0])}, {q(u[1])}, {pcs}, {tot}, "
      f"{q(pay[0])}, {q(pay[1])}, {q(pay[2])}, 0.25);")
    w("SET @b = LAST_INSERT_ID();")
    vals = []
    for ln, (s, qn) in enumerate(lines, 1):
        vals.append(f"({q(CID)}, @b, {ln}, {q(s[0])}, {q(s[1])}, {q(s[2])}, {q(s[3])}, {qn}, {s[4]}, {q(s[6])}, {s[4] * qn})")
        if s[0]:
            k = (bid, s[0], s[3])
            stock_out[k] = stock_out.get(k, 0) + qn
    w("INSERT INTO bill_lines (company_id, bill_id, line_no, item_id, item_name, item_name_en, size_label, qty, rate, cost, amount) VALUES " + ", ".join(vals) + ";")
    # about 1 in 60 bills: the customer comes back and part of it is cancelled with a separate cancel bill (the bill stays)
    if random.random() < 0.017 or idx == len(bills) - 4 or idx == last_demo:
        back = [lines[0]] if len(lines) > 1 else lines
        cancelled.append((no, when, bid, u, back, cust, phone))
w(f"UPDATE companies SET bill_seq = {seq} WHERE id = {q(CID)};")
reasons = ["வாடிக்கையாளர் திருப்பி கொடுத்தார்", "தவறான ஐட்டம் / சைஸ்", "சேதம் (Damage)", "வாங்கவில்லை"]
for n, (ref, when, bid, u, back, cust, phone) in enumerate(cancelled, 1):
    at = (f"TIMESTAMP(CURDATE() - INTERVAL {max(when[1] - random.choice([0, 0, 1, 2]), 0)} DAY) + INTERVAL {min(when[2] + 1800, 79000)} SECOND" if when[0] == "day"
          else "NOW() - INTERVAL 2 MINUTE")
    pcs = sum(qn for _, qn in back)
    tot = sum(s[4] * qn for s, qn in back)
    lj = json.dumps([{"id": s[0], "name": s[1], "en": s[2], "size": s[3], "qty": qn, "rate": float(s[4]), "amt": float(s[4] * qn), "cost": s[6]} for s, qn in back], ensure_ascii=False)
    w("INSERT INTO cancel_bills (company_id, branch_id, cn_seq, cn_no, ref_bill_no, created_at, customer_name, customer_phone, by_user, by_name, pieces, total, reason, lines_json, platform_fee) VALUES "
      f"({q(CID)}, {q(bid)}, {n}, '{CO['prefix']}-C{n:04d}', {q(ref)}, {at}, {q(cust)}, {q(phone)}, {q(u[0])}, {q(u[1])}, {pcs}, {tot}, {q(random.choice(reasons))}, {q(lj)}, 0.25);")
    for s, qn in back:
        if s[0]:
            k = (bid, s[0], s[3])
            stock_out[k] = stock_out.get(k, 0) - qn          # returned pieces are back in stock
w(f"UPDATE companies SET cn_seq = {len(cancelled)} WHERE id = {q(CID)};")

# ---- shop stock: received - sold ----
stock_rows = []
for bi, (bid, _) in enumerate(BR):
    by = USERS[3][1] if bi == 0 else USERS[4][1]
    for s in sizes:
        sold = stock_out.get((bid, s[0], s[3]), 0)
        r = random.random()
        recv = max(0, sold - random.randint(1, 2)) if r < 0.04 else sold if r < 0.10 else sold + random.randint(1, 3) if r < 0.25 else sold + random.randint(5, 30)
        stock_rows.append(f"({q(CID)}, {q(bid)}, {q(s[0])}, {q(s[3])}, {recv - sold}, NOW() - INTERVAL {random.randint(1, 600)} MINUTE, {q(by)})")
for i in range(0, len(stock_rows), 50):
    w("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES " + ", ".join(stock_rows[i:i + 50]) + ";")

# ---- warehouse: purchase orders, stock, transfers ----
wh_stock = {}
by_sup = {0: [s for s in sizes if s[7] in ("fancy", "covering", "gifts")],
          1: [s for s in sizes if s[7] in ("stationery", "toys")],
          2: [s for s in sizes if s[7] == "perfume"]}
po_plan = [(0, 30, "received", 1.0, 1.0), (1, 18, "received", 0.5, 1.0), (2, 6, "partial", 0.0, 0.5), (1, 1, "ordered", 0.0, 0.0)]
for n, (si, ago, status, paidf, recvf) in enumerate(po_plan, 1):
    sid, sname = SUPPLIERS[si][0], SUPPLIERS[si][1]
    pick = random.sample(by_sup[si], min(12, len(by_sup[si])))
    rows, sub, gst = [], 0, 0
    for ln, s in enumerate(pick, 1):
        qty = random.choice([12, 24, 36, 50]) if s[4] < 200 else random.choice([6, 10, 12])
        rq = qty if recvf >= 1 else (qty if ln % 2 else 0) if recvf > 0 else 0
        uc = s[6]
        g = 18 if s[7] in ("perfume", "toys") else 12 if s[7] == "stationery" else 3 if s[7] == "covering" else 5
        amt = round(qty * uc, 2); sub += amt; gst += round(amt * g / 100, 2)
        rows.append(f"(@po, {q(CID)}, {ln}, {q(s[0])}, {q(s[1])}, {q(s[3])}, {qty}, {rq}, {uc}, {g}, {amt})")
        if rq:
            wh_stock[(s[0], s[3])] = wh_stock.get((s[0], s[3]), 0) + rq
    total = round(sub + gst, 2)
    recv = f"NOW() - INTERVAL {max(ago - 1, 0)} DAY" if status != "ordered" else "NULL"
    w("INSERT INTO purchase_orders (company_id, branch_id, po_seq, po_no, supplier_id, supplier_name, order_date, expected_date, supplier_ref, notes, status, "
      "subtotal, gst_total, other_charges, discount, total, paid, created_at, created_by_user, created_by_name, received_at, received_by, updated_at) VALUES "
      f"({q(CID)}, {q(WH[0])}, {n}, 'PO-{n:04d}', {q(sid)}, {q(sname)}, CURDATE() - INTERVAL {ago} DAY, CURDATE() - INTERVAL {max(ago - 2, -2)} DAY, 'BILL-{3300 + n * 17}', "
      f"{q('கொளத்தூர் warehouse-க்கு டெலிவரி')}, {q(status)}, {round(sub, 2)}, {round(gst, 2)}, 0, 0, {total}, {round(total * paidf, 2)}, NOW() - INTERVAL {ago} DAY, 'rasi.wh', 'Murugan (Warehouse)', "
      f"{recv}, {q('Murugan (Warehouse)' if status != 'ordered' else '')}, NOW() - INTERVAL {max(ago - 1, 0)} DAY);")
    w("SET @po = LAST_INSERT_ID();")
    _pd = round(total * paidf, 2)
    if _pd > 0:                                            # supplier payments with dates (advance, then the rest)
        _parts = [_pd] if _pd < 20000 else [round(_pd * 0.4, 2), round(_pd - round(_pd * 0.4, 2), 2)]
        for _i, _a in enumerate(_parts):
            w(f"INSERT INTO po_payments (company_id, po_id, amount, paid_on, mode, ref, created_at, by_name) VALUES ({q(CID)}, @po, {_a}, "
              f"CURDATE() - INTERVAL {max(ago - _i * 7, 0)} DAY, {q(['Bank (NEFT)', 'UPI', 'Cheque'][_i % 3])}, {q('Ref ' + str(5100 + n * 10 + _i))}, NOW(), 'Demo');")
    w("INSERT INTO po_lines (po_id, company_id, line_no, item_id, item_name, size_label, qty, received_qty, unit_cost, gst_pct, amount) VALUES " + ", ".join(rows) + ";")
w(f"UPDATE companies SET po_seq = {len(po_plan)} WHERE id = {q(CID)};")

tr_plan = [(0, 27, "received", False), (1, 26, "received", True), (0, 15, "received", False), (1, 14, "received", False),
           (0, 1, "sent", False), (1, 0, "sent", False)]
keys = sorted(wh_stock)
for n, (bi, ago, status, short) in enumerate(tr_plan, 1):
    rows = []
    for ln, k in enumerate(keys[n - 1::6][:7], 1):
        qty = min(wh_stock[k] - 3, random.choice([4, 6, 10, 12]))
        if qty < 1:
            continue
        rq = qty if status == "received" else 0
        if short and len(rows) == 1:
            rq = qty - 2
        wh_stock[k] -= qty
        nm = next(s[1] for s in sizes if s[0] == k[0] and s[3] == k[1])
        rows.append(f"(@tr, {q(CID)}, {len(rows) + 1}, {q(k[0])}, {q(nm)}, {q(k[1])}, {qty}, {rq})")
    if not rows:
        continue
    rec = f"NOW() - INTERVAL {max(ago - 1, 0)} DAY" if status == "received" else "NULL"
    rby = (USERS[3][1] if bi == 0 else USERS[4][1]) if status == "received" else ""
    w("INSERT INTO stock_transfers (company_id, tr_seq, tr_no, from_branch, to_branch, status, notes, created_at, created_by_user, created_by_name, received_at, received_by) VALUES "
      f"({q(CID)}, {n}, 'TR-{n:04d}', {q(WH[0])}, {q(BR[bi][0])}, {q(status)}, {q('ஆட்டோ TN-05-' + str(4400 + n))}, NOW() - INTERVAL {ago} DAY - INTERVAL 2 HOUR, 'rasi.wh', 'Murugan (Warehouse)', {rec}, {q(rby)});")
    w("SET @tr = LAST_INSERT_ID();")
    w("INSERT INTO transfer_lines (tr_id, company_id, line_no, item_id, item_name, size_label, qty, received_qty) VALUES " + ", ".join(rows) + ";")
w(f"UPDATE companies SET tr_seq = {len(tr_plan)} WHERE id = {q(CID)};")
wh_rows = [f"({q(CID)}, {q(WH[0])}, {q(k[0])}, {q(k[1])}, {v}, NOW() - INTERVAL {random.randint(5, 900)} MINUTE, 'Murugan (Warehouse)')" for k, v in sorted(wh_stock.items())]
for i in range(0, len(wh_rows), 50):
    w("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES " + ", ".join(wh_rows[i:i + 50]) + ";")
for line in demo_orders_sql(CID, sizes, BR, REGULARS + [DEMO_CUSTOMER], random.Random(5)):     # online-shop orders in every state
    w(line)
w("COMMIT;")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(sql) + "\n")
with open(os.path.join(HERE, "rasi-barcodes.tsv"), "w", encoding="utf-8") as f:
    f.write("barcode\titem\titem_en\tsize\trate\n")
    for c in labels:
        f.write("\t".join(str(x) for x in c) + "\n")
print(f"{OUT}: {seq} bills, {len(cancelled)} cancel bills, {len(sizes)} item sizes ({len(labels)} with barcode), "
      f"{len(po_plan)} POs, {len(tr_plan)} transfers, {len(wh_rows)} warehouse stock rows")
