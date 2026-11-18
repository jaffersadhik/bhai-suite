#!/usr/bin/env python3
"""
Generates db/demo/seeds-demo.sql - demo data for "Seeds" Men's Wear Specialist: 2 shops, Palavakkam and Kottivakkam.

  python db/demo/make_seeds_demo.py            -> writes db/demo/seeds-demo.sql (+ seeds-barcodes.tsv)

The SQL file:
  * deletes an earlier Seeds demo (same fixed ids / usernames), so it can be re-run to reset the demo
  * creates the company, the 2 shops, payment modes and 5 users (owner, 2 sales, 2 stock)
  * catalog: 7 groups / 33 men's wear items with sizes (38-46 shirts, S-XXL, 30-38 waist, 80-100 innerwear ...),
    rates, cost prices, EAN-13 barcodes on every size and sample pictures for the online shop
  * ~60 days of bills ending today (a few custom lines, mostly scanned / picked items), cancel bills, an open exchange
    credit for the demo customer, shop stock, 3 suppliers, 4 purchase orders and sample online-shop orders

Run the NEW app version once before importing (it creates the tables / columns this data needs).
"""
import base64, hashlib, json, os, random
from demo_photos import photo_sql, demo_orders_sql
from demo_b2b import b2b_sql

random.seed(20261104)
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "seeds-demo.sql")
DEMO_PASSWORD = "admin1234"
DAYS = 60

CID = "cseedsdemo1"
CO = dict(name="Seeds", tagline="Men's Wear Specialist · ஆண்கள் ஆடை சிறப்பகம்", owner="Seeds Owner",
          phone="9876500003", address="ECR, Palavakkam", city="Chennai", prefix="SEEDS",
          footer="நன்றி! மீண்டும் வருக ❤️ · Seeds Men's Wear")
BR = [("bseedspal01", "பாலவாக்கம் (Palavakkam)"), ("bseedskot01", "கொட்டிவாக்கம் (Kottivakkam)")]
PAY = [("pseedscash", "Cash", "cash"), ("pseedsupi1", "UPI - Seeds", "upi"), ("pseedscard", "Card", "card")]
# username, name, type, branch
USERS = [("seeds.owner", "Seeds Owner", "owner", None),
         ("seeds.pal", "Arun", "sales", BR[0][0]),
         ("seeds.kot", "Imran", "sales", BR[1][0]),
         ("seeds.stock.pal", "Prakash (Stock)", "stock", BR[0][0]),
         ("seeds.stock.kot", "Sathish (Stock)", "stock", BR[1][0])]
SUPPLIERS = [("sseedssup1", "Tiruppur Knit Garments", "9442055501", "33AACCT5678K1Z2", "Avinashi Road, Tiruppur"),
             ("sseedssup2", "Bangalore Shirt Makers", "9845055502", "29AABCB1234M1Z9", "Peenya Industrial Area, Bengaluru"),
             ("sseedssup3", "Erode Dhoti & Lungi Mills", "9443055503", "33AAFCE9012N1Z4", "Brough Road, Erode")]

S = {"shirt": ["38", "40", "42", "44", "46"], "a": ["S", "M", "L", "XL", "XXL"], "w": ["30", "32", "34", "36", "38"],
     "inner": ["80", "85", "90", "95", "100"], "free": ["Free"], "belt": ["32", "34", "36", "38", "40"],
     "veshti": ["Free"], "socks": ["Free"]}
# group id, Tamil, English, emoji, colour, barcoded?, items [Tamil, English, size set, first rate, step]
CAT = [
    ("shirts", "சட்டைகள்", "Shirts", "👔", "#e3e9ff", True, [
        ["ஃபார்மல் சட்டை", "Formal Shirt", "shirt", 799, 50], ["கேஷுவல் சட்டை", "Casual Shirt", "shirt", 699, 50],
        ["லினன் சட்டை", "Linen Shirt", "shirt", 1299, 100], ["செக்டு சட்டை", "Check Shirt", "shirt", 749, 50],
        ["பார்ட்டி வேர் சட்டை", "Party Wear Shirt", "shirt", 1499, 100]]),
    ("tshirts", "டி-ஷர்ட்", "T-Shirts", "👕", "#dff1d8", True, [
        ["ரவுண்ட் நெக் டி-ஷர்ட்", "Round Neck T-Shirt", "a", 399, 30], ["போலோ டி-ஷர்ட்", "Polo T-Shirt", "a", 599, 50],
        ["பிரிண்டட் டி-ஷர்ட்", "Printed T-Shirt", "a", 449, 30], ["ஃபுல் ஸ்லீவ் டி-ஷர்ட்", "Full Sleeve T-Shirt", "a", 549, 50]]),
    ("pants", "பேண்ட் & ஜீன்ஸ்", "Pants & Jeans", "👖", "#ffe7d2", True, [
        ["ஃபார்மல் பேண்ட்", "Formal Trouser", "w", 999, 50], ["ஜீன்ஸ்", "Jeans", "w", 1199, 100],
        ["சினோஸ்", "Chinos", "w", 1099, 50], ["கார்கோ பேண்ட்", "Cargo Pant", "w", 1299, 50]]),
    ("ethnic", "வேஷ்டி & எத்னிக்", "Veshti & Ethnic", "🪷", "#fff0c9", True, [
        ["காட்டன் வேஷ்டி", "Cotton Veshti", "veshti", 399, 0], ["பட்டு கரை வேஷ்டி", "Silk Border Veshti", "veshti", 899, 0],
        ["லுங்கி", "Lungi", "free", 299, 0], ["குர்தா", "Kurta", "a", 899, 50], ["வேஷ்டி சட்டை செட்", "Veshti Shirt Set", "shirt", 1499, 100]]),
    ("inner", "இன்னர்வேர்", "Innerwear", "🩲", "#f1e4f6", True, [
        ["பனியன் (Vest)", "Vest", "inner", 149, 10], ["பிரீஃப் (3 பேக்)", "Brief 3-pack", "inner", 349, 20],
        ["ட்ரங்க்", "Trunk", "inner", 199, 10], ["ஜிம் வெஸ்ட்", "Gym Vest", "a", 249, 20]]),
    ("casual", "ட்ராக் & ஷார்ட்ஸ்", "Track & Shorts", "🩳", "#dcefec", True, [
        ["ட்ராக் பேண்ட்", "Track Pant", "a", 599, 50], ["பெர்முடா ஷார்ட்ஸ்", "Bermuda Shorts", "a", 449, 30],
        ["நைட் ஷார்ட்ஸ்", "Night Shorts", "a", 299, 20], ["ஜாகர்ஸ்", "Joggers", "a", 799, 50]]),
    ("acc", "ஆக்சசரீஸ்", "Accessories", "👞", "#fde2e2", True, [
        ["லெதர் பெல்ட்", "Leather Belt", "belt", 499, 0], ["லெதர் வாலட்", "Leather Wallet", "free", 599, 0],
        ["சாக்ஸ் (3 பேர்)", "Socks 3-pair", "socks", 199, 0], ["கைக்குட்டை (6 பேக்)", "Handkerchief 6-pack", "free", 149, 0],
        ["டை", "Tie", "free", 349, 0], ["கேப்", "Cap", "free", 249, 0], ["சன்கிளாஸ்", "Sunglasses", "free", 699, 0]]),
]
# what the counter usually types in as a custom item: (name, group, size, rate choices)
CUSTOM = [("ஆல்டரேஷன் (Alteration)", "Alteration", "pants", "", [50, 80, 100]), ("பேண்ட் ஹெம்மிங்", "Pant Hemming", "pants", "", [40, 50]),
          ("கிஃப்ட் பேக்கிங்", "Gift Packing", "acc", "", [20, 30]), ("பழைய ஸ்டாக் சட்டை", "Old Stock Shirt", "shirts", "40", [399, 499]),
          ("ஆஃபர் டி-ஷர்ட்", "Offer T-Shirt", "tshirts", "L", [199, 249]), ("கர்ச்சீஃப் (லூஸ்)", "Kerchief loose", "acc", "", [20, 30])]
CUSTOMERS = ["Rahul", "Arjun", "Karthik", "Vignesh", "Suresh", "Ramesh", "Mohammed", "Imran", "Sathish", "Prakash",
             "Dinesh", "Senthil", "Ashok", "Bala", "Gopal", "Harish", "Naveen", "Faizal", "Joseph", "Selvam"]
DEMO_CUSTOMER = ("Rahul", "9840067890")    # use this number to show "cancel from a bill" (பில்லிலிருந்து ரத்து)
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


w("-- Seeds Men's Wear demo data - generated by make_seeds_demo.py. Re-import to reset the demo.")
w("SET NAMES utf8mb4;")
w("SET time_zone = '+05:30';   -- the app stores India time; NOW() / CURDATE() below must be India time too")
w("START TRANSACTION;")
w(f"DELETE t FROM auth_tokens t JOIN users u ON u.id = t.user_id WHERE u.company_id = '{CID}' OR u.username LIKE 'seeds.%';")
w(f"DELETE FROM users WHERE company_id = '{CID}' OR username LIKE 'seeds.%';")
w(f"DELETE FROM bill_lines WHERE company_id = '{CID}';")
w(f"DELETE FROM bills WHERE company_id = '{CID}';")
w(f"DELETE FROM companies WHERE id = '{CID}';")   # cascades: branches, stock, groups, items, POs, transfers, invoices ...

w("INSERT INTO companies (id, name, tagline, owner_name, phone, address, city, gstin, email, bill_prefix, show_bismillah, bill_footer, "
  "bill_seq, catalog_version, catalog_setup, archived, bill_fee, created_at, updated_at) VALUES "
  f"({q(CID)}, {q(CO['name'])}, {q(CO['tagline'])}, {q(CO['owner'])}, {q(CO['phone'])}, {q(CO['address'])}, {q(CO['city'])}, '', '', "
  f"{q(CO['prefix'])}, 0, {q(CO['footer'])}, 0, 1, 1, 0, 0.25, NOW() - INTERVAL {DAYS + 5} DAY, NOW());")
for i, (bid, bname) in enumerate(BR):
    w(f"INSERT INTO branches (company_id, id, name, kind, sort_order, created_at) VALUES ({q(CID)}, {q(bid)}, {q(bname)}, 'shop', {i}, NOW() - INTERVAL {DAYS + 5} DAY);")
w(f"UPDATE companies SET gstin = '33AAMFS5678N1Z3' WHERE id = {q(CID)};")   # demo GSTIN (for tax invoices)
w(f"UPDATE companies SET shop_code = 'seedsdemo', shop_enabled = 1 WHERE id = {q(CID)};")   # online shop: shop.html?s=seedsdemo
for i, (pid, pname, kind) in enumerate(PAY):
    w(f"INSERT INTO pay_modes (company_id, id, name, kind, sort_order, active) VALUES ({q(CID)}, {q(pid)}, {q(pname)}, {q(kind)}, {i}, 1);")
for i, (un, name, typ, bid) in enumerate(USERS):
    salt, h = pbkdf2(DEMO_PASSWORD)
    w("INSERT INTO users (id, username, full_name, user_type, company_id, branch_id, pass_salt, pass_hash, active, must_change, created_at, updated_at) VALUES "
      f"('useedsdemo{i:02d}', {q(un)}, {q(name)}, {q(typ)}, {q(CID)}, {q(bid)}, {q(salt)}, {q(h)}, 1, 0, NOW() - INTERVAL {DAYS + 5} DAY, NOW());")
for sid, sn, sp, sg, sa in SUPPLIERS:
    w(f"INSERT INTO suppliers (company_id, id, name, phone, gstin, address, created_at) VALUES ({q(CID)}, {q(sid)}, {q(sn)}, {q(sp)}, {q(sg)}, {q(sa)}, NOW() - INTERVAL {DAYS} DAY);")

# ---- catalog ----
WRATE = {}          # (item_id, size) -> wholesale price
sizes = []      # (item_id, ta, en, size, rate, popularity, cost, group)
labels = []
code_no = 0
for gi, (gid, gta, gen, emo, col, coded, items) in enumerate(CAT):
    w(f"INSERT INTO item_groups (company_id, id, name_ta, name_en, emoji, color, sort_order) VALUES ({q(CID)}, {q(gid)}, {q(gta)}, {q(gen)}, {q(emo)}, {q(col)}, {gi});")
    for ii, (ta, en, sset, base, step) in enumerate(items):
        iid = f"s_{gid}_{ii}"
        w(f"INSERT INTO items (company_id, id, group_id, name_ta, name_en, sort_order) VALUES ({q(CID)}, {q(iid)}, {q(gid)}, {q(ta)}, {q(en)}, {gi * 10 + ii});")
        w(photo_sql(CID, iid, en, emo, col))                     # sample picture for the online shop
        margin = random.uniform(0.52, 0.66)
        pop = random.uniform(0.6, 1.6) * (1.6 if gid in ("shirts", "tshirts", "pants", "inner") else 1.0)
        for si, sz in enumerate(S[sset]):
            rate = base + step * si
            code = None
            if coded:                                                   # every men's wear size carries a barcode tag
                code_no += 1
                code = ean13("8908{:08d}".format(41100000 + code_no))
                labels.append((code, ta, en, sz, rate))
            cost = round(rate * margin) if rate >= 50 else round(rate * margin, 1)
            wrate = max(int(round(rate * 0.78 / 5) * 5), int(cost or 0) + 5)       # wholesale selling price ~22 % below retail
            WRATE[(iid, sz)] = wrate
            w(f"INSERT INTO item_sizes (company_id, item_id, size_label, rate, wrate, cost, barcode, sort_order) VALUES ({q(CID)}, {q(iid)}, {q(sz)}, {rate}, {wrate}, {q(cost)}, {q(code)}, {si});")
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
                if random.random() < 0.12:          # custom item typed at the counter (alteration, offer stock ...)
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
# wholesale: retailers buying on credit, tax invoices (PREFIX-W....) and their payments
_b2b, _last = b2b_sql(CID, CO["prefix"], q, sizes, [b[0] for b in BR], {BR[0][0]: (USERS[3][0], USERS[3][1]), BR[1][0]: (USERS[4][0], USERS[4][1])},
                      seq, stock_out, random.Random(21), WRATE)
for _l in _b2b:
    w(_l)
w(f"UPDATE companies SET bill_seq = {_last} WHERE id = {q(CID)};")
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
by_sup = {0: [s for s in sizes if s[7] in ("tshirts", "inner", "casual")],
          1: [s for s in sizes if s[7] in ("shirts", "pants", "acc")],
          2: [s for s in sizes if s[7] == "ethnic"]}
po_plan = [(0, 30, "received", 1.0, 1.0), (1, 18, "received", 0.5, 1.0), (2, 6, "partial", 0.0, 0.5), (1, 1, "ordered", 0.0, 0.0)]
for n, (si, ago, status, paidf, recvf) in enumerate(po_plan, 1):
    sid, sname = SUPPLIERS[si][0], SUPPLIERS[si][1]
    pick = random.sample(by_sup[si], min(12, len(by_sup[si])))
    rows, sub, gst = [], 0, 0
    for ln, s in enumerate(pick, 1):
        qty = random.choice([12, 24, 36, 50]) if s[4] < 200 else random.choice([6, 10, 12])
        rq = qty if recvf >= 1 else (qty if ln % 2 else 0) if recvf > 0 else 0
        uc = s[6]
        g = 12 if s[4] > 1000 else 5            # GST on garments: 5 % up to ₹1000, 12 % above
        amt = round(qty * uc, 2); sub += amt; gst += round(amt * g / 100, 2)
        rows.append(f"(@po, {q(CID)}, {ln}, {q(s[0])}, {q(s[1])}, {q(s[3])}, {qty}, {rq}, {uc}, {g}, {amt})")
        if rq:
            wh_stock[(s[0], s[3])] = wh_stock.get((s[0], s[3]), 0) + rq
    total = round(sub + gst, 2)
    recv = f"NOW() - INTERVAL {max(ago - 1, 0)} DAY" if status != "ordered" else "NULL"
    w("INSERT INTO purchase_orders (company_id, branch_id, po_seq, po_no, supplier_id, supplier_name, order_date, expected_date, supplier_ref, notes, status, "
      "subtotal, gst_total, other_charges, discount, total, paid, created_at, created_by_user, created_by_name, received_at, received_by, updated_at) VALUES "
      f"({q(CID)}, {q(BR[n % 2][0])}, {n}, 'PO-{n:04d}', {q(sid)}, {q(sname)}, CURDATE() - INTERVAL {ago} DAY, CURDATE() - INTERVAL {max(ago - 2, -2)} DAY, 'BILL-{3300 + n * 17}', "
      f"{q('கடைக்கு நேரடி டெலிவரி')}, {q(status)}, {round(sub, 2)}, {round(gst, 2)}, 0, 0, {total}, {round(total * paidf, 2)}, NOW() - INTERVAL {ago} DAY, 'seeds.stock.pal', 'Prakash (Stock)', "
      f"{recv}, {q('Prakash (Stock)' if status != 'ordered' else '')}, NOW() - INTERVAL {max(ago - 1, 0)} DAY);")
    w("SET @po = LAST_INSERT_ID();")
    _pd = round(total * paidf, 2)
    if _pd > 0:                                            # supplier payments with dates (advance, then the rest)
        _parts = [_pd] if _pd < 20000 else [round(_pd * 0.4, 2), round(_pd - round(_pd * 0.4, 2), 2)]
        for _i, _a in enumerate(_parts):
            w(f"INSERT INTO po_payments (company_id, po_id, amount, paid_on, mode, ref, created_at, by_name) VALUES ({q(CID)}, @po, {_a}, "
              f"CURDATE() - INTERVAL {max(ago - _i * 7, 0)} DAY, {q(['Bank (NEFT)', 'UPI', 'Cheque'][_i % 3])}, {q('Ref ' + str(5100 + n * 10 + _i))}, NOW(), 'Demo');")
    w("INSERT INTO po_lines (po_id, company_id, line_no, item_id, item_name, size_label, qty, received_qty, unit_cost, gst_pct, amount) VALUES " + ", ".join(rows) + ";")
w(f"UPDATE companies SET po_seq = {len(po_plan)} WHERE id = {q(CID)};")

for line in demo_orders_sql(CID, sizes, BR, REGULARS + [DEMO_CUSTOMER], random.Random(5)):     # online-shop orders in every state
    w(line)
w("COMMIT;")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(sql) + "\n")
with open(os.path.join(HERE, "seeds-barcodes.tsv"), "w", encoding="utf-8") as f:
    f.write("barcode\titem\titem_en\tsize\trate\n")
    for c in labels:
        f.write("\t".join(str(x) for x in c) + "\n")
print(f"{OUT}: {seq} bills, {len(cancelled)} cancel bills, {len(sizes)} item sizes ({len(labels)} with barcode), "
      f"{len(po_plan)} POs")
