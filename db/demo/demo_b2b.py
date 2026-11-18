"""Wholesale demo data: retailers (parties), tax invoices on credit (bills with bill_kind 'invoice') and payments."""
import random

RETAILERS = [("Kumar Textiles", "Kumar", "Tambaram", "33AAAPK1234L1Z5", 30), ("Sri Murugan Readymades", "Senthil", "Chromepet", "33ABCPM5678K1Z2", 30),
             ("New Fashion Point", "Faizal", "Pallavaram", "", 15), ("Lakshmi Garments", "Lakshmi", "Guduvanchery", "33AAEPL9012M1Z8", 45),
             ("Royal Collections", "Joseph", "Medavakkam", "", 30), ("Anbu Stores", "Anbu", "Kelambakkam", "", 15)]


def b2b_sql(cid, prefix, q, sizes, branches, staff, start_seq, stock_out, rnd=None, wrate=None):
    """staff: {branch_id: (username, name)}; returns (sql lines, last bill seq)"""
    rnd = rnd or random.Random(11)
    out, seq = [], start_seq
    for k, (name, contact, city, gst, days) in enumerate(RETAILERS):
        pid = f"rdemo{k:02d}{cid[-3:]}"
        phone = "98" + str(rnd.randint(40000000, 49999999))
        out.append("INSERT INTO parties (company_id, id, name, contact, phone, gstin, address, city, credit_days, created_at) VALUES "
                   f"({q(cid)}, {q(pid)}, {q(name)}, {q(contact)}, {q(phone)}, {q(gst)}, {q('Main Road, ' + city)}, {q(city)}, {days}, NOW() - INTERVAL 70 DAY);")
        for j in range(rnd.randint(2, 5)):
            ago = rnd.randint(1, 60)
            br = branches[k % len(branches)]
            user = staff[br]
            picks = rnd.sample(sizes, rnd.randint(2, 5))
            lines = [(s, rnd.choice([6, 10, 12, 20, 24]), (wrate or {}).get((s[0], s[3])) or round(s[4] * 0.78)) for s in picks]
            taxable = sum(qn * r for _, qn, r in lines)
            gst_pct = 5
            gst = round(taxable * gst_pct / 100, 2)
            total = round(taxable + gst, 2)
            # older invoices are paid, newer ones partly, some left unpaid (a few overdue)
            r = rnd.random()
            paid = total if (ago > days and r < 0.7) or r < 0.3 else round(total * rnd.choice([0, 0.3, 0.5]), 0)
            seq += 1
            no = f"{prefix}-W{seq:04d}"
            pcs = sum(qn for _, qn, _ in lines)
            at = f"TIMESTAMP(CURDATE() - INTERVAL {ago} DAY) + INTERVAL {rnd.randint(36000, 70000)} SECOND"
            out.append("INSERT INTO bills (company_id, branch_id, bill_seq, bill_no, created_at, customer_name, customer_phone, billed_by_user, billed_by_name, pieces, total, "
                       "pay_mode_id, pay_name, pay_kind, platform_fee, bill_kind, party_id, due_date, taxable, gst_pct, gst_amount, paid_amount, inv_notes) VALUES "
                       f"({q(cid)}, {q(br)}, {seq}, {q(no)}, {at}, {q(name)}, {q(phone)}, {q(user[0])}, {q(user[1])}, {pcs}, {total}, NULL, 'Credit (Invoice)', 'credit', 0.25, "
                       f"'invoice', {q(pid)}, CURDATE() - INTERVAL {ago - days} DAY, {taxable}, {gst_pct}, {gst}, {paid}, 'Transport: local');")
            out.append("SET @b = LAST_INSERT_ID();")
            out.append("INSERT INTO bill_lines (company_id, bill_id, line_no, item_id, item_name, item_name_en, size_label, qty, rate, cost, amount) VALUES " + ", ".join(
                f"({q(cid)}, @b, {ln}, {q(s[0])}, {q(s[1])}, {q(s[2])}, {q(s[3])}, {qn}, {rt}, {q(s[6])}, {qn * rt})" for ln, (s, qn, rt) in enumerate(lines, 1)) + ";")
            for s, qn, _ in lines:
                stock_out[(br, s[0], s[3])] = stock_out.get((br, s[0], s[3]), 0) + qn
            if paid > 0:
                parts = [paid] if paid < total or rnd.random() < 0.6 else [round(paid * 0.5, 0), round(paid - round(paid * 0.5, 0), 2)]
                for n, amt in enumerate(parts):
                    out.append("INSERT INTO party_payments (company_id, party_id, bill_id, amount, paid_on, mode, ref, created_at, by_name) VALUES "
                               f"({q(cid)}, {q(pid)}, @b, {amt}, CURDATE() - INTERVAL {max(ago - 5 - n * 10, 0)} DAY, {q(rnd.choice(['Cash', 'UPI', 'Bank (NEFT)', 'Cheque']))}, "
                               f"{q('Ref ' + str(rnd.randint(1000, 9999)))}, NOW() - INTERVAL {max(ago - 5 - n * 10, 0)} DAY, {q(user[1])});")
    return out, seq
