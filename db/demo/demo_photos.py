"""
Sample item pictures for the demo companies (illustrations, not real product photos): a big emoji on a soft
gradient with the English item name. Used by make_*_demo.py to fill item_photos so the online shop has pictures.
Needs Pillow + the Noto Color Emoji font (only to regenerate the SQL; the SQL itself has the JPEG bytes).
"""
import io, os
from PIL import Image, ImageDraw, ImageFont

EMOJI_FONT = "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"
TEXT_FONT = next((p for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"] if os.path.exists(p)), None)
# keyword in the English name -> emoji (first match wins)
PICK = [("t-shirt", "👕"), ("party wear", "🤵"), ("veshti shirt", "🤵"), ("shirt", "👔"), ("trouser", "👖"), ("jeans", "👖"),
        ("chinos", "👖"), ("cargo", "👖"), ("veshti", "🪷"), ("lungi", "🪷"), ("kurta", "🥻"), ("gym vest", "🎽"), ("vest", "🎽"),
        ("brief", "🩲"), ("trunk", "🩲"), ("track", "🏃"), ("bermuda", "🩳"), ("shorts", "🩳"), ("joggers", "🏃"),
        ("belt", "🪢"), ("wallet", "👛"), ("socks", "🧦"), ("handkerchief", "🤧"), ("tie", "👔"), ("cap", "🧢"), ("sunglasses", "🕶️"),
        ("teddy", "🧸"), ("toy car", "🚗"), ("remote", "🏎️"), ("doll", "🪆"), ("puzzle", "🧩"), ("balloon", "🎈"),
        ("notebook", "📒"), ("pen box", "🖊️"), ("pencil box", "✏️"), ("geometry", "📐"), ("colour", "🖍️"), ("wrap", "🎁"),
        ("school bag", "🎒"), ("photo frame", "🖼️"), ("show piece", "🏺"), ("mug", "☕"), ("hamper", "🧺"), ("clock", "🕰️"),
        ("body spray", "🧴"), ("perfume", "🌸"), ("attar", "🪔"), ("roll-on", "🧴"), ("hair clip", "📎"), ("hair band", "🎀"),
        ("bangle", "💫"), ("earring", "💎"), ("jhumka", "🔔"), ("bindi", "🔴"), ("anklet", "✨"), ("chain", "📿"),
        ("necklace", "💍"), ("ring", "💍"), ("nighty", "👗"), ("night suit", "🩱"), ("churidar", "👗"), ("anarkali", "👗"),
        ("kurti", "👚"), ("saree", "🥻"), ("abaya", "🧕"), ("hijab", "🧕"), ("leggings", "👖"), ("palazzo", "👖"),
        ("patiala", "👖"), ("bra", "👙"), ("panty", "🩲"), ("petticoat", "👗"), ("slip", "🎽"), ("blouse", "👚"), ("dupatta", "🧣")]


def emoji_for(en, fallback):
    low = en.lower()
    return next((e for k, e in PICK if k in low), fallback)


def _shade(hexcol, f):
    h = hexcol.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return tuple(max(0, min(255, int(c * f))) for c in (r, g, b))


def photo_jpeg(en, emoji, color, size=480):
    img = Image.new("RGB", (size, size), "#ffffff")
    top, bot = _shade(color, 1.02), _shade(color, 0.82)
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / size
        d.line([(0, y), (size, y)], fill=tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)))
    d.ellipse([size * .16, size * .12, size * .84, size * .80], fill=_shade(color, 1.12))
    ef = ImageFont.truetype(EMOJI_FONT, 109)
    em = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
    ImageDraw.Draw(em).text((80, 80), emoji, font=ef, embedded_color=True, anchor="mm")
    em = em.resize((int(size * .52), int(size * .52)), Image.LANCZOS)
    img.paste(em, (int((size - em.width) / 2), int(size * .19)), em)
    if TEXT_FONT:
        tf = ImageFont.truetype(TEXT_FONT, 30)
        d.rounded_rectangle([size * .06, size * .80, size * .94, size * .94], radius=18, fill=(255, 255, 255))
        d.text((size / 2, size * .87), en[:26], font=tf, fill=(30, 50, 40), anchor="mm")
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=80, optimize=True)
    return buf.getvalue()


def photo_sql(cid, item_id, en, emoji_fallback, color):
    data = photo_jpeg(en, emoji_for(en, emoji_fallback), color)
    return (f"INSERT INTO item_photos (company_id, item_id, mime, data, updated_at) VALUES ('{cid}', '{item_id}', 'image/jpeg', "
            f"X'{data.hex()}', NOW());")


def demo_orders_sql(cid, sizes, shops, customers, rnd):
    """A few online-shop orders in different states. sizes: (item_id, ta, en, size, rate, ...); shops: [(branch_id, name)]"""
    import json, secrets
    plan = [("new", 0, "delivery", 0), ("new", 1, "pickup", 0), ("accepted", 0, "delivery", 1), ("out", 1, "delivery", 1),
            ("delivered", 0, "delivery", 3), ("cancelled", 1, "delivery", 4)]
    out = []
    for n, (st, bi, mode, ago) in enumerate(plan, 1):
        name, phone = customers[n % len(customers)]
        picks = rnd.sample(sizes, 3)
        lines = [{"id": s[0], "name": s[1], "en": s[2], "size": s[3], "qty": q, "rate": float(s[4]), "amt": float(s[4] * q)}
                 for s, q in zip(picks, [1, 2, 1])]
        tot = sum(l["amt"] for l in lines)
        pcs = sum(l["qty"] for l in lines)
        addr = "" if mode == "pickup" else f"No. {rnd.randint(2, 99)}, {rnd.choice(['Gandhi St', 'Anna Nagar 2nd St', 'Lake View Rd', 'Temple St'])}, Chennai"
        note = {"cancelled": "வாடிக்கையாளர் கேட்டுக்கொண்டபடி ரத்து", "out": "டெலிவரி பையன்: Ravi 98400 77777"}.get(st, "")
        bill = "" if st in ("new", "accepted", "cancelled") else "(SELECT MAX(bill_no) FROM bills WHERE company_id = '%s' AND branch_id = '%s')" % (cid, shops[bi][0])
        out.append("INSERT INTO shop_orders (company_id, order_seq, order_no, token, branch_id, name, phone, address, mode, notes, lines_json, pieces, total, status, status_note, handled_by, bill_no, created_at, updated_at) VALUES "
                   f"('{cid}', {n}, 'ORD-{n:04d}', '{secrets.token_hex(12)}', '{shops[bi][0]}', '{name}', '{phone}', '{addr}', '{mode}', '', "
                   "'" + json.dumps(lines, ensure_ascii=False).replace("'", "''") + f"', {pcs}, {tot}, '{st}', '{note}', '{'' if st == 'new' else 'Demo staff'}', "
                   f"{bill or chr(39) * 2}, NOW() - INTERVAL {ago} DAY - INTERVAL {rnd.randint(10, 300)} MINUTE, NOW() - INTERVAL {ago} DAY);")
    out.append(f"UPDATE companies SET order_seq = {len(plan)} WHERE id = '{cid}';")
    return out
