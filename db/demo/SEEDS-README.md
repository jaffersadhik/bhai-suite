# Seeds — Men's Wear Specialist (demo data)

Two shops: **பாலவாக்கம் (Palavakkam)** and **கொட்டிவாக்கம் (Kottivakkam)**.

* 7 groups, 33 items, 129 item × size, all with EAN-13 barcodes (`seeds-barcodes.tsv`; print tags from the app → 🏷 Barcode Labels)
  and sample pictures for the online shop:
  Shirts (38–46) · T-Shirts (S–XXL) · Pants & Jeans (30–38) · Veshti & Ethnic · Innerwear (80–100) · Track & Shorts · Accessories
* ~1700 bills over the last 60 days (today included), cost prices (profit report), cancel bills, shop stock,
  3 suppliers (Tiruppur, Bengaluru, Erode), 4 purchase orders, 6 online-shop orders in different states.

## Load it
Deploy the new app version and start it once, then:
```bash
docker compose exec -T mysql mysql -u root -p bmb < db/demo/seeds-demo.sql
```
Running it again resets the Seeds demo. Other companies are not touched.

## Logins (password for all: `admin1234`)

| Username | Who | Type | Shop |
|---|---|---|---|
| `seeds.owner` | Seeds Owner | Owner — reports, stock, POs, orders, invoice | both |
| `seeds.pal` | Arun | Sales | பாலவாக்கம் |
| `seeds.kot` | Imran | Sales | கொட்டிவாக்கம் |
| `seeds.stock.pal` | Prakash | Stock | பாலவாக்கம் |
| `seeds.stock.kot` | Sathish | Stock | கொட்டிவாக்கம் |

## Demo flow
* Billing: group → shirt → size 40 → ADD, or 📷 scan a printed tag → WhatsApp bill (ends with ❤️ + online-shop link).
* Regular customer **Rahul — 9840067890**: many bills in both shops → ✖ cancel from a bill by mobile number;
  he also has an unused cancel bill → 🔁 exchange on a new bill.
* Online shop: `https://<your-server>/shop.html?s=seedsdemo&m=9840067890` → order → as `seeds.pal`: 🛒 Online ஆர்டர்கள்.
* Owner: sales / profit by shop and salesman, stock report, payment-wise (Cash / UPI - Seeds / Card).

Regenerate: `python db/demo/make_seeds_demo.py`
