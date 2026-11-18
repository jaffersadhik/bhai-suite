# Liba Fashion — demo data

Demo company **Liba Fashion Readymade Showroom** (Ladies Special) with 2 shop branches, the
**திருவான்மியூர் Warehouse (Thiruvanmiyur)**, 6 users,
31 ladies-wear items (114 item × size with barcodes and cost prices — a few left without cost to show it is optional), ~1000 bills over the last 60 days, payments
(Cash / Card / UPI - Jafar / UPI - Sadik) and branch-wise stock.

Warehouse flow in the demo: 3 suppliers → 5 purchase orders delivered to the warehouse (3 received,
1 partly received, 1 ordered) → 8 stock transfers warehouse → shops (6 received, two of them one piece
short; 2 still in transit — one for each shop, so both stock users have something to "receive" live).

## Load it (EC2 / Docker)

1. Deploy the new app version first and let it start once (it adds the new columns):
   `docker compose up -d --build`
2. Import (run from the project folder; asks for the MySQL root password):

   ```bash
   docker compose exec -T mysql mysql -u root -p bmb < db/demo/liba-demo.sql
   ```

   Windows PowerShell:

   ```powershell
   Get-Content db\demo\liba-demo.sql -Encoding UTF8 | docker compose exec -T mysql mysql -u root -p bmb
   ```

Bill dates are relative to the day you import, so "today" always has sales.
**Running it again resets the demo** (deletes the old Liba data first). Other companies are not touched.

## Logins (password for all: `admin1234`)

| Username | Who | Type | Branch |
|---|---|---|---|
| `liba.owner` | Liba Owner | Owner — sales + stock reports only | all |
| `liba.jafar` | Jafar | Sales | பாலவாக்கம் (Palavakkam) |
| `liba.sadik` | Sadik | Sales | செங்கல்பட்டு (Chengalpattu) |
| `liba.stock.pal` | Nasreen | Stock | பாலவாக்கம் (Palavakkam) |
| `liba.stock.cgl` | Farhana | Stock | செங்கல்பட்டு (Chengalpattu) |
| `liba.wh` | Imran | 🏭 Warehouse — POs, warehouse stock, transfers to shops, all-branch stock report (no billing) | திருவான்மியூர் Warehouse |

### Suggested warehouse demo (5 minutes)
1. `liba.wh` → warehouse home: stock, pending POs, pieces in transit. Open **PO-0004** (partly received) → receive the rest.
2. **கிளைக்கு சரக்கு அனுப்பு** → choose Palavakkam, tap items / sizes, send → TR-0009 (warehouse stock drops at once).
3. `liba.stock.pal` → Stock screen shows "Warehouse-லிருந்து … transfer வந்துள்ளது" → open → count → receive (enter one less to show a shortage).
4. `liba.owner` → 📦 ஸ்டாக் tab: shops and the warehouse are shown separately, plus "in transit"; 🚚 Transfer tab lists everything (view only).

Change the passwords (or delete the demo users) before the customer uses it for real.

## Files

* `liba-demo.sql` — the data (import this)
* `make_liba_demo.py` — regenerates the SQL (`python db/demo/make_liba_demo.py`)
* `liba-barcodes.tsv` — every demo barcode with item, size and rate
* `liba-demo-barcodes.pdf` — printable labels; scan them with the app during the demo

### Demo: cancel from a bill
Regular customer **Ayesha — 9840054321** has many bills in both shops over the last months. Log in as a sales / stock user →
menu → **✖ பில் ரத்து — மொபைல் எண் மூலம்** → 9840054321 → pick a bill → choose items → save.

### Demo: online shop
Shop link: `https://<your-server>/shop.html?s=libademo&m=9840054321` (it is also at the end of every WhatsApp bill).
Items have sample pictures (illustrations made for the demo — replace them with real photos from ✏️ item edit → 📷).
Six sample orders are already there (new, accepted, out for delivery, delivered, cancelled). Place one from the shop page,
then as a sales user: menu → 🛒 Online ஆர்டர்கள் → accept → convert to bill → save → out for delivery → delivered,
and refresh the customer's status page.

