# mHotel — Hotel / Restaurant Maintenance

The 4th WAR in the bhai-suite (one Tomcat, one JVM). Mirrors the **mschool** module's architecture
and design (Spring Boot + MySQL, same green/gold look). Runs on **port 8083**, database **mhotel**.
Adding it does **not** touch MBill (:8080), MSchool (:8081) or the website (:8082).

## Features (v1)
- **Dining** — tables (free/busy), open a table → take order → bill → payment → table frees.
- **Takeaway** — quick order with customer name/phone.
- **Delivery** — Swiggy / Zomato / other orders, entered manually or via the webhook (below).
- **Billing** — subtotal, GST/tax %, discount, total; bill number per order.
- **WhatsApp bill share** — builds the bill text + a `wa.me` link to the customer's number.
- **Menu** — items by category with price (admin / cashier).
- **Users** — roles: admin (all), cashier (billing + setup), waiter (orders only).

## Roles & first login
`admin / admin1234` is created on first start — change it in **More → password**.

## Delivery-partner webhook (Swiggy / Zomato)
Real Swiggy/Zomato integration needs their **partner API credentials** (their APIs are gated, not public),
so this ships a normalised intake endpoint you can point an integration / middleware at:

```
POST /api/delivery/webhook
Header: X-Webhook-Secret: <MHOTEL_WEBHOOK_SECRET>
Body:   {"partner":"swiggy","ref":"SW123","customer":"Ravi","phone":"98xxxxxxxx",
         "items":[{"name":"Idli","qty":2,"price":40},{"name":"Dosa","qty":1,"price":60}]}
```
Set `MHOTEL_WEBHOOK_SECRET` in `.env` to enable it (empty = disabled).

## Build & run
Part of the suite — the normal command builds and runs all four apps:
```
docker compose up -d --build        # or: bash deploy/deploy-mhotel.sh
```
Standalone (dev): `mvn -pl mhotel -am package` then `java -jar mhotel/target/mhotel.war`
(set `MHOTEL_DB_URL` / `MHOTEL_DB_USER` / `MHOTEL_DB_PASS`, default port 8083).

## Environment variables
| var | meaning | default |
|---|---|---|
| `MHOTEL_DB_URL` | JDBC url for the `mhotel` schema | mysql://mysql:3306/mhotel |
| `MHOTEL_DB_USER` / `MHOTEL_DB_PASS` | same credentials as MBill | `DB_USER` / `DB_PASS` |
| `MHOTEL_ZONE` | time zone for "today"/receipts | Asia/Kolkata |
| `MHOTEL_WEBHOOK_SECRET` | delivery webhook secret (empty = off) | (empty) |
| `HOTEL_PORT` | published port | 127.0.0.1:8083 |

## Note on login security
Unlike MBill/MSchool this v1 does **not** yet use the shared `suite-security` (captcha + IP block).
It uses a simple username/password + session token. It can be upgraded to `suite-security` later without
changing the API shape (`POST /api/login → {token}`).
