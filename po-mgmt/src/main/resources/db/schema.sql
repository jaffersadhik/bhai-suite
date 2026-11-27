-- Purchase Order Management schema (database/schema: po_mgmt).
-- Every statement is CREATE TABLE IF NOT EXISTS, so re-running on each start is safe.
-- Column changes for existing databases go in com.pomgmt.core.Migrations (runs after this).

CREATE TABLE IF NOT EXISTS users (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  username    VARCHAR(30)   NOT NULL,
  full_name   VARCHAR(80)   NOT NULL,
  role        VARCHAR(24)   NOT NULL DEFAULT 'purchase_executive',
  pass_salt   VARCHAR(32)   NOT NULL,
  pass_hash   VARCHAR(64)   NOT NULL,
  active      TINYINT(1)    NOT NULL DEFAULT 1,
  created_at  DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sessions (
  token_hash  VARCHAR(64)   NOT NULL,
  user_id     BIGINT        NOT NULL,
  created_at  DATETIME      NOT NULL,
  last_seen   DATETIME      NOT NULL,
  PRIMARY KEY (token_hash),
  KEY idx_sessions_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS branches (
  id        BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT       NOT NULL DEFAULT 0,
  code     VARCHAR(8)   NOT NULL,
  name     VARCHAR(60)  NOT NULL,
  address  VARCHAR(200) NOT NULL DEFAULT '',
  active   TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_branch_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS suppliers (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id      BIGINT       NOT NULL DEFAULT 0,
  name           VARCHAR(100) NOT NULL,
  gstin          VARCHAR(20)  NOT NULL DEFAULT '',
  address        VARCHAR(200) NOT NULL DEFAULT '',
  contact_person VARCHAR(60)  NOT NULL DEFAULT '',
  mobile         VARCHAR(20)  NOT NULL DEFAULT '',
  payment_terms  VARCHAR(60)  NOT NULL DEFAULT '',
  credit_days    INT          NOT NULL DEFAULT 0,
  created_at     DATETIME     NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agencies (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  supplier_id BIGINT       NOT NULL,
  name        VARCHAR(100) NOT NULL,
  location    VARCHAR(60)  NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  KEY idx_agency_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS items (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT        NOT NULL DEFAULT 0,
  item_code   VARCHAR(40)   NOT NULL,
  barcode     VARCHAR(40)   NOT NULL DEFAULT '',
  category    VARCHAR(40)   NOT NULL DEFAULT '',
  brand       VARCHAR(40)   NOT NULL DEFAULT '',
  description VARCHAR(120)  NOT NULL,
  colour      VARCHAR(30)   NOT NULL DEFAULT '',
  hsn         VARCHAR(12)   NOT NULL DEFAULT '',
  gst_pct     DECIMAL(5,2)  NOT NULL DEFAULT 5,
  active      TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_item_code (tenant_id, item_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS item_sizes (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  item_id    BIGINT      NOT NULL,
  size       VARCHAR(12) NOT NULL,
  sort_order INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_isize_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- approved purchase budgets: qty by period + branch + item (size optional); revisions keep history
CREATE TABLE IF NOT EXISTS budgets (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL DEFAULT 0,
  period      VARCHAR(40)  NOT NULL,
  branch_id   BIGINT       NOT NULL,
  item_id     BIGINT       NOT NULL,
  size        VARCHAR(12)  NOT NULL DEFAULT '',
  budget_qty  INT          NOT NULL DEFAULT 0,
  prepared_by VARCHAR(40)  NOT NULL DEFAULT '',
  approved_by VARCHAR(40)  NOT NULL DEFAULT '',
  status      VARCHAR(12)  NOT NULL DEFAULT 'approved',   -- approved | revised
  revision    INT          NOT NULL DEFAULT 1,
  created_at  DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_budget_lookup (period, branch_id, item_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- parent purchase entry: one entry -> many branch POs, linked by common_ref
CREATE TABLE IF NOT EXISTS purchase_entries (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id       BIGINT       NOT NULL DEFAULT 0,
  common_ref      VARCHAR(20)  NOT NULL,
  supplier_id     BIGINT       NOT NULL,
  agency_id       BIGINT       NULL,
  period          VARCHAR(40)  NOT NULL DEFAULT '',
  order_book_ref  VARCHAR(60)  NOT NULL DEFAULT '',
  order_book_file VARCHAR(255) NOT NULL DEFAULT '',
  ordered_by      VARCHAR(80)  NOT NULL DEFAULT '',
  remarks         VARCHAR(255) NOT NULL DEFAULT '',
  delivery_date   VARCHAR(12)  NOT NULL DEFAULT '',
  status          VARCHAR(12)  NOT NULL DEFAULT 'pending',
  created_at      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_entry_ref (common_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- child branch PO
CREATE TABLE IF NOT EXISTS purchase_orders (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  tenant_id        BIGINT        NOT NULL DEFAULT 0,
  po_number        VARCHAR(24)   NOT NULL,
  entry_id         BIGINT        NOT NULL,
  branch_id        BIGINT        NOT NULL,
  status           VARCHAR(12)   NOT NULL DEFAULT 'pending',   -- draft|pending|approved|rejected|sent|partial|completed|cancelled
  ordered_by       VARCHAR(80)   NOT NULL DEFAULT '',
  approved_by      VARCHAR(80)   NOT NULL DEFAULT '',
  approved_at      DATETIME      NULL,
  reject_reason    VARCHAR(200)  NOT NULL DEFAULT '',
  over_budget      TINYINT(1)    NOT NULL DEFAULT 0,
  exception_reason VARCHAR(200)  NOT NULL DEFAULT '',
  subtotal         DECIMAL(14,2) NOT NULL DEFAULT 0,
  created_at       DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_po_number (po_number),
  KEY idx_po_entry (entry_id),
  KEY idx_po_branch (branch_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS po_lines (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  po_id        BIGINT        NOT NULL,
  item_id      BIGINT        NOT NULL,
  size         VARCHAR(12)   NOT NULL DEFAULT '',
  qty          INT           NOT NULL DEFAULT 0,
  rate         DECIMAL(10,2) NOT NULL DEFAULT 0,
  amount       DECIMAL(14,2) NOT NULL DEFAULT 0,
  received_qty INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_line_po (po_id),
  KEY idx_line_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_history (
  id        BIGINT       NOT NULL AUTO_INCREMENT,
  entity    VARCHAR(24)  NOT NULL,
  entity_id BIGINT       NOT NULL,
  action    VARCHAR(40)  NOT NULL,
  actor     VARCHAR(80)  NOT NULL DEFAULT '',
  detail    VARCHAR(255) NOT NULL DEFAULT '',
  at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_audit_entity (entity, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS counters (
  name VARCHAR(20) NOT NULL,
  val  BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
