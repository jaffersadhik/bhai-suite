-- =====================================================================
-- MBill (Mobile Bill) - MySQL schema (MySQL 8.0.16 or newer)
-- Runs automatically at application start (spring.sql.init.mode=always).
-- Every statement is CREATE TABLE IF NOT EXISTS, so re-running is safe.
--
-- Company data separation (multi-tenant):
--   * every business table has company_id NOT NULL
--   * primary keys and foreign keys between business tables include company_id,
--     so an item can only belong to a group of the SAME company, a size only to
--     an item of the SAME company, a bill line only to a bill of the SAME company
--   * the application takes company_id only from the logged-in session (never from
--     the browser) and every query filters by it
--
-- utf8mb4 is required for Tamil text and emoji.
-- =====================================================================

-- ---------------------------------------------------------------------
-- companies : one row per shop / customer company
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS companies (
  id               VARCHAR(20)   NOT NULL,
  name             VARCHAR(60)   NOT NULL,
  tagline          VARCHAR(60)   NOT NULL DEFAULT '',
  owner_name       VARCHAR(60)   NOT NULL DEFAULT '',
  phone            CHAR(10)      NOT NULL,
  address          VARCHAR(200)  NOT NULL DEFAULT '',
  city             VARCHAR(40)   NOT NULL DEFAULT '',
  gstin            VARCHAR(15)   NOT NULL DEFAULT '',
  email            VARCHAR(80)   NOT NULL DEFAULT '',
  bill_prefix      VARCHAR(8)    NOT NULL DEFAULT 'INV',
  show_bismillah   TINYINT(1)    NOT NULL DEFAULT 0,   -- unused (religious greeting removed)
  bill_footer      VARCHAR(120)  NOT NULL DEFAULT '',
  bill_seq         BIGINT        NOT NULL DEFAULT 0,
  catalog_version  BIGINT        NOT NULL DEFAULT 0,
  catalog_setup    TINYINT(1)    NOT NULL DEFAULT 0,
  archived         TINYINT(1)    NOT NULL DEFAULT 0,
  created_at       DATETIME      NOT NULL,
  updated_at       DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_companies_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- branches : shops / outlets of a company (every company has at least one, "Main")
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS branches (
  company_id   VARCHAR(20)  NOT NULL,
  id           VARCHAR(20)  NOT NULL,
  name         VARCHAR(40)  NOT NULL,
  kind         VARCHAR(10)  NOT NULL DEFAULT 'shop',      -- shop (sells) | warehouse (stock + transfers only)
  sort_order   INT          NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL,
  PRIMARY KEY (company_id, id),
  CONSTRAINT fk_branches_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- users : admin (no company) | owner (company reports only) | sales (billing, one branch)
--         | stock (sales + stock maintenance, one branch)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
  id           VARCHAR(20)  NOT NULL,
  username     VARCHAR(30)  NOT NULL,
  full_name    VARCHAR(60)  NOT NULL,
  user_type    ENUM('admin','owner','sales','stock','warehouse','system') NOT NULL,
  company_id   VARCHAR(20)  NULL,
  branch_id    VARCHAR(20)  NULL,
  pass_salt    VARCHAR(44)  NOT NULL,
  pass_hash    VARCHAR(64)  NOT NULL,
  active       TINYINT(1)   NOT NULL DEFAULT 1,
  must_change  TINYINT(1)   NOT NULL DEFAULT 0,
  max_sessions TINYINT      NOT NULL DEFAULT 2,    -- simultaneous logins allowed for this username (0 = unlimited)
  idle_minutes INT          NOT NULL DEFAULT 10,   -- auto-logout after this many idle minutes (0 = never)
  lang         VARCHAR(5)   NOT NULL DEFAULT '',   -- the user's chosen app language ('' = English default)
  created_by   VARCHAR(20)  NULL,                  -- the admin / system user who created this user (an admin only sees his own)
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_username (username),
  KEY idx_users_company (company_id),
  CONSTRAINT fk_users_company FOREIGN KEY (company_id) REFERENCES companies (id),
  CONSTRAINT chk_users_company CHECK ((user_type IN ('admin','system') AND company_id IS NULL) OR (user_type NOT IN ('admin','system') AND company_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- auth_tokens : login sessions (only the SHA-256 of the token is stored)
--   company_id = company an ADMIN has opened for billing (customers use users.company_id)
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
-- translations : online translations of master data / screen phrases (Tamil source text -> language), shared by all users
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS translations (
  src_hash CHAR(32)     NOT NULL,                  -- md5 of the Tamil source text
  lang     VARCHAR(8)   NOT NULL,
  src      VARCHAR(320) NOT NULL,
  txt      VARCHAR(640) NOT NULL,
  PRIMARY KEY (src_hash, lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS auth_tokens (
  token_hash   CHAR(64)     NOT NULL,
  user_id      VARCHAR(20)  NOT NULL,
  company_id   VARCHAR(20)  NULL,
  expires_at   DATETIME     NOT NULL,
  created_at   DATETIME     NOT NULL,
  last_seen    DATETIME     NULL,                  -- last request with this access token (idle timeout)
  ip           VARCHAR(45)  NULL,
  device       VARCHAR(120) NULL,
  PRIMARY KEY (token_hash),
  KEY idx_tokens_user (user_id),
  KEY idx_tokens_expires (expires_at),
  CONSTRAINT fk_tokens_user    FOREIGN KEY (user_id)    REFERENCES users (id)     ON DELETE CASCADE,
  CONSTRAINT fk_tokens_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- item_groups : Nighty, Churidar, Saree ... (per company)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS item_groups (
  company_id   VARCHAR(20)  NOT NULL,
  id           VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  name_ta      VARCHAR(30)  NOT NULL,
  name_en      VARCHAR(30)  NOT NULL DEFAULT '',
  emoji        VARCHAR(16)  NOT NULL DEFAULT '',
  color        CHAR(7)      NOT NULL DEFAULT '#e8eef0',
  sort_order   INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (company_id, id),
  CONSTRAINT fk_groups_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- items : products inside a group (same company enforced by composite FK)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS items (
  company_id   VARCHAR(20)  NOT NULL,
  id           VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  group_id     VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  name_ta      VARCHAR(60)  NOT NULL,
  name_en      VARCHAR(60)  NOT NULL DEFAULT '',
  sort_order   INT          NOT NULL DEFAULT 0,
  sold_by      VARCHAR(6)   NOT NULL DEFAULT 'count',   -- count | weight
  wt_unit      VARCHAR(2)   NOT NULL DEFAULT 'kg',      -- weight items: kg | g
  PRIMARY KEY (company_id, id),
  KEY idx_items_group (company_id, group_id),
  CONSTRAINT fk_items_group FOREIGN KEY (company_id, group_id) REFERENCES item_groups (company_id, id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- item_sizes : size-wise rate (and optional barcode) of an item
--   barcode is unique inside a company (NULL = no barcode); older databases get the
--   column from BillingService.migrate() at startup
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS item_sizes (
  company_id   VARCHAR(20)    NOT NULL,
  item_id      VARCHAR(40)    COLLATE utf8mb4_bin NOT NULL,
  size_label   VARCHAR(12)    COLLATE utf8mb4_bin NOT NULL,
  rate         DECIMAL(10,2)  NOT NULL,
  cost         DECIMAL(10,2)  NULL,          -- அடக்க விலை (purchase cost), optional
  barcode      VARCHAR(40)    NULL,
  sort_order   INT            NOT NULL DEFAULT 0,
  PRIMARY KEY (company_id, item_id, size_label),
  UNIQUE KEY uk_sizes_barcode (company_id, barcode),
  CONSTRAINT fk_sizes_item FOREIGN KEY (company_id, item_id) REFERENCES items (company_id, id) ON DELETE CASCADE,
  CONSTRAINT chk_sizes_rate CHECK (rate > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- bills : one row per bill, numbered separately for every company
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bills (
  id               BIGINT         NOT NULL AUTO_INCREMENT,
  company_id       VARCHAR(20)    NOT NULL,
  branch_id        VARCHAR(20)    NULL,
  bill_seq         BIGINT         NOT NULL,
  bill_no          VARCHAR(20)    NOT NULL,
  created_at       DATETIME       NOT NULL,
  customer_name    VARCHAR(60)    NOT NULL DEFAULT '',
  customer_phone   VARCHAR(10)    NOT NULL DEFAULT '',
  billed_by_user   VARCHAR(30)    NOT NULL DEFAULT '',
  billed_by_name   VARCHAR(60)    NOT NULL DEFAULT '',
  pieces           INT            NOT NULL,
  total            DECIMAL(12,2)  NOT NULL,
  pay_mode_id      VARCHAR(20)    NULL,
  pay_name         VARCHAR(30)    NOT NULL DEFAULT '',
  pay_kind         VARCHAR(8)     NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bills_company_id  (company_id, id),
  UNIQUE KEY uk_bills_company_seq (company_id, bill_seq),
  KEY idx_bills_company_date (company_id, created_at),
  KEY idx_bills_branch_date (company_id, branch_id, created_at),
  CONSTRAINT fk_bills_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- bill_lines : items of a bill (same company enforced by composite FK)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bill_lines (
  company_id     VARCHAR(20)    NOT NULL,
  bill_id        BIGINT         NOT NULL,
  line_no        INT            NOT NULL,
  item_id        VARCHAR(40)    NULL,
  item_name      VARCHAR(60)    NOT NULL,
  item_name_en   VARCHAR(60)    NOT NULL DEFAULT '',
  size_label     VARCHAR(12)    NOT NULL DEFAULT '',
  qty            INT            NOT NULL,
  rate           DECIMAL(10,2)  NOT NULL,
  cost           DECIMAL(10,2)  NULL,          -- unit cost price at billing time (for profit), NULL = unknown
  amount         DECIMAL(12,2)  NOT NULL,
  PRIMARY KEY (bill_id, line_no),
  KEY idx_lines_company_bill (company_id, bill_id),
  CONSTRAINT fk_lines_bill FOREIGN KEY (company_id, bill_id) REFERENCES bills (company_id, id) ON DELETE CASCADE,
  CONSTRAINT chk_lines_qty CHECK (qty BETWEEN 1 AND 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- stock : pieces in hand per branch, item and size. Bills reduce it,
--   stock users set / adjust it. Negative values are allowed.
--   No FK to item_sizes on purpose: the catalog is re-written on every save.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock (
  company_id   VARCHAR(20)  NOT NULL,
  branch_id    VARCHAR(20)  NOT NULL,
  item_id      VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  size_label   VARCHAR(12)  COLLATE utf8mb4_bin NOT NULL,
  qty          INT          NOT NULL DEFAULT 0,
  updated_at   DATETIME     NOT NULL,
  updated_by   VARCHAR(60)  NOT NULL DEFAULT '',
  PRIMARY KEY (company_id, branch_id, item_id, size_label),
  CONSTRAINT fk_stock_branch FOREIGN KEY (company_id, branch_id) REFERENCES branches (company_id, id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- pay_modes : how a bill was paid, per company - e.g. Cash, Card,
--   "UPI - Jafar", "UPI - Sadik" (kind groups them: cash / card / upi / other).
--   Bills keep a copy of the name + kind, so renaming never changes old bills.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pay_modes (
  company_id   VARCHAR(20)  NOT NULL,
  id           VARCHAR(20)  NOT NULL,
  name         VARCHAR(30)  NOT NULL,
  kind         ENUM('cash','card','upi','other') NOT NULL DEFAULT 'cash',
  sort_order   INT          NOT NULL DEFAULT 0,
  active       TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (company_id, id),
  CONSTRAINT fk_pay_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- pending_bills : a cart saved for a customer's mobile number, not billed yet.
--   Several salesmen may add pending for the same number; "save + WhatsApp"
--   joins today's open pending of that number (same branch) into ONE bill.
--   Pending never changes stock or reports.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pending_bills (
  id               BIGINT         NOT NULL AUTO_INCREMENT,
  company_id       VARCHAR(20)    NOT NULL,
  branch_id        VARCHAR(20)    NULL,
  phone            VARCHAR(10)    NOT NULL,
  customer_name    VARCHAR(60)    NOT NULL DEFAULT '',
  lines_json       MEDIUMTEXT     NOT NULL,
  pieces           INT            NOT NULL,
  total            DECIMAL(12,2)  NOT NULL,
  status           VARCHAR(8)     NOT NULL DEFAULT 'open',     -- open | billed | deleted
  bill_id          BIGINT         NULL,
  created_at       DATETIME       NOT NULL,
  created_by_user  VARCHAR(30)    NOT NULL DEFAULT '',
  created_by_name  VARCHAR(60)    NOT NULL DEFAULT '',
  closed_at        DATETIME       NULL,
  PRIMARY KEY (id),
  KEY idx_pending_lookup (company_id, branch_id, phone, status, created_at),
  CONSTRAINT fk_pending_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- suppliers / purchase_orders / po_lines : purchase orders entered by stock users.
--   Receiving goods adds to the branch stock (and can update the item's cost price).
--   status: ordered -> partial -> received | cancelled
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS suppliers (
  company_id   VARCHAR(20)   NOT NULL,
  id           VARCHAR(20)   NOT NULL,
  name         VARCHAR(60)   NOT NULL,
  phone        VARCHAR(10)   NOT NULL DEFAULT '',
  gstin        VARCHAR(15)   NOT NULL DEFAULT '',
  address      VARCHAR(200)  NOT NULL DEFAULT '',
  created_at   DATETIME      NOT NULL,
  PRIMARY KEY (company_id, id),
  CONSTRAINT fk_suppliers_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS purchase_orders (
  id               BIGINT         NOT NULL AUTO_INCREMENT,
  company_id       VARCHAR(20)    NOT NULL,
  branch_id        VARCHAR(20)    NULL,
  po_seq           BIGINT         NOT NULL,
  po_no            VARCHAR(20)    NOT NULL,
  supplier_id      VARCHAR(20)    NULL,
  supplier_name    VARCHAR(60)    NOT NULL DEFAULT '',
  order_date       DATE           NOT NULL,
  expected_date    DATE           NULL,
  supplier_ref     VARCHAR(40)    NOT NULL DEFAULT '',
  notes            VARCHAR(300)   NOT NULL DEFAULT '',
  status           VARCHAR(10)    NOT NULL DEFAULT 'ordered',
  subtotal         DECIMAL(14,2)  NOT NULL DEFAULT 0,
  gst_total        DECIMAL(14,2)  NOT NULL DEFAULT 0,
  other_charges    DECIMAL(14,2)  NOT NULL DEFAULT 0,
  discount         DECIMAL(14,2)  NOT NULL DEFAULT 0,
  total            DECIMAL(14,2)  NOT NULL DEFAULT 0,
  paid             DECIMAL(14,2)  NOT NULL DEFAULT 0,
  created_at       DATETIME       NOT NULL,
  created_by_user  VARCHAR(30)    NOT NULL DEFAULT '',
  created_by_name  VARCHAR(60)    NOT NULL DEFAULT '',
  assigned_user    VARCHAR(30)    NOT NULL DEFAULT '',   -- warehouse / stock user this PO is allocated to (handles / receives it)
  assigned_name    VARCHAR(60)    NOT NULL DEFAULT '',
  received_at      DATETIME       NULL,
  received_by      VARCHAR(60)    NOT NULL DEFAULT '',
  updated_at       DATETIME       NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_po_company_seq (company_id, po_seq),
  KEY idx_po_branch (company_id, branch_id, status),
  KEY idx_po_assigned (company_id, assigned_user, status),
  CONSTRAINT fk_po_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS po_lines (
  po_id          BIGINT         NOT NULL,
  company_id     VARCHAR(20)    NOT NULL,
  line_no        INT            NOT NULL,
  item_id        VARCHAR(40)    COLLATE utf8mb4_bin NULL,
  item_name      VARCHAR(60)    NOT NULL,
  size_label     VARCHAR(12)    COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  qty            INT            NOT NULL,          -- count lines: pieces; weight lines (wt_unit set): GRAMS
  received_qty   INT            NOT NULL DEFAULT 0,
  unit_cost      DECIMAL(10,2)  NOT NULL DEFAULT 0, -- per piece, or per kg / per g (wt_unit) for weight lines
  gst_pct        DECIMAL(5,2)   NOT NULL DEFAULT 0,
  amount         DECIMAL(14,2)  NOT NULL DEFAULT 0,
  wt_unit        VARCHAR(2)     NULL,               -- NULL = counted in pieces; 'kg' | 'g' = bought by weight, price per that unit
  PRIMARY KEY (po_id, line_no),
  CONSTRAINT fk_po_lines FOREIGN KEY (po_id) REFERENCES purchase_orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- stock_transfers / transfer_lines : stock sent from a warehouse to a shop.
--   sent: source stock already reduced (in transit) -> received: shop stock added
--   (received_qty < qty = shortage) | cancelled: pieces returned to the source.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_transfers (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  company_id       VARCHAR(20)   NOT NULL,
  tr_seq           BIGINT        NOT NULL,
  tr_no            VARCHAR(20)   NOT NULL,
  from_branch      VARCHAR(20)   NOT NULL,
  to_branch        VARCHAR(20)   NOT NULL,
  status           VARCHAR(10)   NOT NULL DEFAULT 'sent',
  notes            VARCHAR(200)  NOT NULL DEFAULT '',
  created_at       DATETIME      NOT NULL,
  created_by_user  VARCHAR(30)   NOT NULL DEFAULT '',
  created_by_name  VARCHAR(60)   NOT NULL DEFAULT '',
  received_at      DATETIME      NULL,
  received_by      VARCHAR(60)   NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tr_company_seq (company_id, tr_seq),
  KEY idx_tr_to (company_id, to_branch, status),
  KEY idx_tr_from (company_id, from_branch, status),
  CONSTRAINT fk_tr_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS transfer_lines (
  tr_id          BIGINT        NOT NULL,
  company_id     VARCHAR(20)   NOT NULL,
  line_no        INT           NOT NULL,
  item_id        VARCHAR(40)   COLLATE utf8mb4_bin NOT NULL,
  item_name      VARCHAR(60)   NOT NULL,
  size_label     VARCHAR(12)   COLLATE utf8mb4_bin NOT NULL,
  qty            INT           NOT NULL,
  received_qty   INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (tr_id, line_no),
  CONSTRAINT fk_tr_lines FOREIGN KEY (tr_id) REFERENCES stock_transfers (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- platform_invoices : what each company owes for using MBill (Mobile Bill).
--   one invoice per company per month = bills of that month x the per-bill fee
--   (companies.bill_fee, default 0.25, snapshotted on each bill as bills.platform_fee).
--   Generated on the 2nd of every month at 8 AM for the previous month.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform_invoices (
  id            BIGINT         NOT NULL AUTO_INCREMENT,
  company_id    VARCHAR(20)    NOT NULL,
  inv_no        VARCHAR(30)    NOT NULL,
  month         CHAR(7)        NOT NULL,
  bill_count    INT            NOT NULL DEFAULT 0,
  cancel_count  INT            NOT NULL DEFAULT 0,
  fee           DECIMAL(8,2)   NOT NULL DEFAULT 0.25,
  amount        DECIMAL(14,2)  NOT NULL DEFAULT 0,
  status        VARCHAR(10)    NOT NULL DEFAULT 'unpaid',
  generated_at  DATETIME       NOT NULL,
  paid_at       DATETIME       NULL,
  paid_amount   DECIMAL(14,2)  NOT NULL DEFAULT 0,
  paid_ref      VARCHAR(60)    NOT NULL DEFAULT '',
  paid_by       VARCHAR(60)    NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inv_company_month (company_id, month),
  UNIQUE KEY uk_inv_no (inv_no),
  KEY idx_inv_month (month, status),
  CONSTRAINT fk_inv_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- cancel_bills : ரத்து பில் — made like a normal bill (the cancelled / returned items are added), with its own
--   number (PREFIX-C0001). The original bill is never changed. Catalog pieces go back to stock.
--   Every cancel bill is charged in the software invoice, same as a bill.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cancel_bills (
  id              BIGINT         NOT NULL AUTO_INCREMENT,
  company_id      VARCHAR(20)    NOT NULL,
  branch_id       VARCHAR(20)    NULL,
  cn_seq          BIGINT         NOT NULL,
  cn_no           VARCHAR(30)    NOT NULL,
  ref_bill_no     VARCHAR(30)    NOT NULL DEFAULT '',
  created_at      DATETIME       NOT NULL,
  customer_name   VARCHAR(60)    NOT NULL DEFAULT '',
  customer_phone  VARCHAR(10)    NOT NULL DEFAULT '',
  by_user         VARCHAR(30)    NOT NULL DEFAULT '',
  by_name         VARCHAR(60)    NOT NULL DEFAULT '',
  pieces          INT            NOT NULL DEFAULT 0,
  total           DECIMAL(12,2)  NOT NULL DEFAULT 0,
  reason          VARCHAR(120)   NOT NULL DEFAULT '',
  lines_json      MEDIUMTEXT     NOT NULL,
  platform_fee    DECIMAL(8,2)   NULL,
  used_bill_no    VARCHAR(30)    NOT NULL DEFAULT '',   -- the new bill this cancel bill was exchanged against
  used_at         DATETIME       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cn_company_seq (company_id, cn_seq),
  KEY idx_cn_company (company_id, branch_id, created_at),
  CONSTRAINT fk_cn_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- item_photos : one photo per item (resized in the browser, JPEG ~50 KB), shown in the app and the online shop.
--   No FK to items on purpose: saving the catalog rewrites items, the photo must stay.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS item_photos (
  company_id   VARCHAR(20)    NOT NULL,
  item_id      VARCHAR(40)    COLLATE utf8mb4_bin NOT NULL,
  mime         VARCHAR(20)    NOT NULL,
  data         MEDIUMBLOB     NOT NULL,
  updated_at   DATETIME       NOT NULL,
  PRIMARY KEY (company_id, item_id),
  CONSTRAINT fk_photos_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- shop_orders : orders placed by customers on the online shop page (/shop.html?s=<shop_code>).
--   new -> accepted -> billed (turned into a bill, bill_no) -> out (out for delivery) -> delivered | cancelled
--   token = the customer's secret link to see the status.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shop_orders (
  id            BIGINT         NOT NULL AUTO_INCREMENT,
  company_id    VARCHAR(20)    NOT NULL,
  order_seq     BIGINT         NOT NULL,
  order_no      VARCHAR(20)    NOT NULL,
  token         VARCHAR(40)    NOT NULL,
  branch_id     VARCHAR(20)    NOT NULL,
  name          VARCHAR(60)    NOT NULL,
  phone         VARCHAR(10)    NOT NULL,
  address       VARCHAR(300)   NOT NULL DEFAULT '',
  mode          VARCHAR(10)    NOT NULL DEFAULT 'delivery',
  notes         VARCHAR(300)   NOT NULL DEFAULT '',
  lines_json    MEDIUMTEXT     NOT NULL,
  pieces        INT            NOT NULL DEFAULT 0,
  total         DECIMAL(12,2)  NOT NULL DEFAULT 0,
  status        VARCHAR(12)    NOT NULL DEFAULT 'new',
  status_note   VARCHAR(120)   NOT NULL DEFAULT '',
  handled_by    VARCHAR(60)    NOT NULL DEFAULT '',
  bill_no       VARCHAR(30)    NOT NULL DEFAULT '',
  source        VARCHAR(10)    NOT NULL DEFAULT 'online',   -- online (shop page) | staff (taken by a sales / stock user)
  taken_by      VARCHAR(60)    NOT NULL DEFAULT '',
  created_at    DATETIME       NOT NULL,
  updated_at    DATETIME       NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_orders_token (token),
  UNIQUE KEY uk_orders_seq (company_id, order_seq),
  KEY idx_orders_branch (company_id, branch_id, status),
  CONSTRAINT fk_orders_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- parties : wholesale customers (retail shops that buy from this company on credit).
--   Their tax invoices are rows in bills with bill_kind = 'invoice' (party_id, due_date, taxable, gst_*, paid_amount).
-- party_payments : money received from a retailer, each row applied to one invoice.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS parties (
  company_id    VARCHAR(20)   NOT NULL,
  id            VARCHAR(20)   NOT NULL,
  name          VARCHAR(80)   NOT NULL,
  contact       VARCHAR(60)   NOT NULL DEFAULT '',
  phone         VARCHAR(10)   NOT NULL,
  gstin         VARCHAR(20)   NOT NULL DEFAULT '',
  address       VARCHAR(300)  NOT NULL DEFAULT '',
  city          VARCHAR(40)   NOT NULL DEFAULT '',
  credit_days   INT           NOT NULL DEFAULT 30,
  created_at    DATETIME      NOT NULL,
  PRIMARY KEY (company_id, id),
  CONSTRAINT fk_parties_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS party_payments (
  id            BIGINT         NOT NULL AUTO_INCREMENT,
  company_id    VARCHAR(20)    NOT NULL,
  party_id      VARCHAR(20)    NOT NULL,
  bill_id       BIGINT         NULL,
  amount        DECIMAL(12,2)  NOT NULL,
  paid_on       DATE           NOT NULL,
  mode          VARCHAR(30)    NOT NULL DEFAULT 'Cash',
  ref           VARCHAR(60)    NOT NULL DEFAULT '',
  created_at    DATETIME       NOT NULL,
  by_name       VARCHAR(60)    NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  KEY idx_pp_party (company_id, party_id),
  CONSTRAINT fk_pp_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- po_payments : every payment made to a supplier against a purchase order (date, amount, mode, ref).
--   purchase_orders.paid = SUM(amount) of its rows.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS po_payments (
  id            BIGINT         NOT NULL AUTO_INCREMENT,
  company_id    VARCHAR(20)    NOT NULL,
  po_id         BIGINT         NOT NULL,
  amount        DECIMAL(14,2)  NOT NULL,
  paid_on       DATE           NOT NULL,
  mode          VARCHAR(30)    NOT NULL DEFAULT 'Cash',
  ref           VARCHAR(60)    NOT NULL DEFAULT '',
  created_at    DATETIME       NOT NULL,
  by_name       VARCHAR(60)    NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  KEY idx_pop_po (po_id),
  KEY idx_pop_company (company_id, paid_on),
  CONSTRAINT fk_pop_po FOREIGN KEY (po_id) REFERENCES purchase_orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- test_runs / test_results : post-deploy (live) tests - one run after every start-up / deploy, or by the admin.
CREATE TABLE IF NOT EXISTS test_runs (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  build        VARCHAR(60)  NOT NULL,
  prev_build   VARCHAR(60)  NULL,
  trigger_by   VARCHAR(60)  NOT NULL,
  started_at   DATETIME     NOT NULL,
  finished_at  DATETIME     NULL,
  status       VARCHAR(10)  NOT NULL,
  total        INT          NOT NULL DEFAULT 0,
  passed       INT          NOT NULL DEFAULT 0,
  failed       INT          NOT NULL DEFAULT 0,
  warned       INT          NOT NULL DEFAULT 0,
  regressions  INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY ix_test_runs_build (build)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS test_results (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  run_id       BIGINT       NOT NULL,
  code         VARCHAR(160) NOT NULL,
  category     VARCHAR(20)  NOT NULL,
  name         VARCHAR(160) NOT NULL,
  status       VARCHAR(6)   NOT NULL,
  detail       VARCHAR(500) NOT NULL DEFAULT '',
  metric       DOUBLE       NULL,
  ms           BIGINT       NOT NULL DEFAULT 0,
  prev_status  VARCHAR(6)   NULL,
  regression   TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY ix_test_results_run (run_id, code),
  CONSTRAINT fk_test_results_run FOREIGN KEY (run_id) REFERENCES test_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- bill_no_blocks : bill / cancel bill numbers reserved for a device that works offline (taken out of the company counter,
--   printed on the bills made without internet; the bill keeps this number when it is sent later)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bill_no_blocks (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  company_id  VARCHAR(20)   NOT NULL,
  device_id   VARCHAR(40)   NOT NULL,
  kind        VARCHAR(6)    NOT NULL,                 -- bill | cancel
  from_seq    BIGINT        NOT NULL,
  to_seq      BIGINT        NOT NULL,
  user_name   VARCHAR(30)   NOT NULL DEFAULT '',
  created_at  DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_blocks_dev (company_id, device_id, kind, from_seq),
  CONSTRAINT fk_blocks_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
