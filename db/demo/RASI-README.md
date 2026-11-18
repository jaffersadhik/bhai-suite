# Rasi — demo data (Kolathur, Chennai)

**Rasi** — Fancy · Gold Covering · Stationery · Toys · Gifts · Body spray / Perfume / Attar.

* Shops: **முகாம்பிகை தெரு (Mugambigai St)** and **ஸ்கூல் அருகில் (Near School)**, plus **🏭 கொளத்தூர் Warehouse (Kolathur)**
* 6 groups, 34 items, 62 item × size (Small / Medium / Big, 2.2–2.8 bangles, 18"–30" chains, 3/6/12 ml attar, 150/200 ml spray …)
* Billing is mostly by hand: group → item → size (→ rate), and about half of the bill lines are **custom items**
  (key chain, greeting card, return gift, mehndi cone, loose attar …). Only packed goods (stationery, toys,
  perfumes — 28 sizes) carry a barcode (`rasi-barcodes.tsv`; print labels from the app: 🏷 Barcode Labels).
* ~1300 bills over the last 45 days (today included), about 28 cancel bills (separate ரத்து பில் documents — the original bills stay), shop + warehouse stock,
  3 suppliers, 4 purchase orders at the warehouse, 6 transfers (one in transit to each shop).

## Load it
Deploy the new app version and start it once (adds the new tables / columns), then:

```bash
docker compose exec -T mysql mysql -u root -p bmb < db/demo/rasi-demo.sql
```
```powershell
Get-Content db\demo\rasi-demo.sql -Encoding UTF8 | docker compose exec -T mysql mysql -u root -p bmb
```
Running it again resets the Rasi demo (dates are relative to the import day). Other companies are not touched.

## Logins (password for all: `admin1234`)

| Username | Who | Type | Where |
|---|---|---|---|
| `rasi.owner` | Rasi Owner | Owner — reports, stock, POs, transfers, software invoice | all |
| `rasi.mug` | Karthik | Sales | முகாம்பிகை தெரு |
| `rasi.sch` | Divya | Sales | ஸ்கூல் அருகில் |
| `rasi.stock.mug` | Meena | Stock | முகாம்பிகை தெரு |
| `rasi.stock.sch` | Saranya | Stock | ஸ்கூல் அருகில் |
| `rasi.wh` | Murugan | 🏭 Warehouse | கொளத்தூர் Warehouse |

Regenerate: `python db/demo/make_rasi_demo.py`

### Demo: cancel from a bill
Regular customer **Priya — 9840012345** has many bills in both shops over the last months. Log in as a sales / stock user →
menu → **✖ பில் ரத்து — மொபைல் எண் மூலம்** → 9840012345 → pick a bill → choose items → save.

### Demo: exchange
Priya (9840012345) has an unused cancel bill (₹450). Log in as `rasi.mug` → add items worth more than ₹450 → View bill →
mobile 9840012345 → tick the 🔁 Exchange cancel bill → the balance to collect is shown → pick Cash / UPI → save.

### Demo: online shop
Shop link: `https://<your-server>/shop.html?s=rasidemo&m=9840012345` (it is also at the end of every WhatsApp bill).
Items have sample pictures (illustrations made for the demo — replace them with real photos from ✏️ item edit → 📷).
Six sample orders are already there (new, accepted, out for delivery, delivered, cancelled). Place one from the shop page,
then as a sales user: menu → 🛒 Online ஆர்டர்கள் → accept → convert to bill → save → out for delivery → delivered,
and refresh the customer's status page.

