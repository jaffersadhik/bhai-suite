-- mHotel schema. Every statement is CREATE TABLE IF NOT EXISTS, so re-running on each start is safe.
-- Column changes for existing databases go in com.mhotel.core.Migrations (runs after this).

CREATE TABLE IF NOT EXISTS users (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  username    VARCHAR(30)   NOT NULL,
  full_name   VARCHAR(60)   NOT NULL,
  role        VARCHAR(10)   NOT NULL DEFAULT 'waiter',   -- admin | cashier | waiter
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

CREATE TABLE IF NOT EXISTS settings (
  k    VARCHAR(40)  NOT NULL,
  val  VARCHAR(200) NOT NULL DEFAULT '',
  PRIMARY KEY (k)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS menu_items (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  name        VARCHAR(60)   NOT NULL,
  name_ta     VARCHAR(60)   NOT NULL DEFAULT '',
  category    VARCHAR(30)   NOT NULL DEFAULT 'General',
  price       DECIMAL(10,2) NOT NULL DEFAULT 0,
  active      TINYINT(1)    NOT NULL DEFAULT 1,
  created_at  DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_menu_cat (category, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dining_tables (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  label       VARCHAR(20)   NOT NULL,
  seats       INT           NOT NULL DEFAULT 0,
  sort_order  INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- one row per order / bill. type = dine_in | takeaway | delivery.
CREATE TABLE IF NOT EXISTS orders (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  type            VARCHAR(10)   NOT NULL,
  table_id        BIGINT        NULL,
  partner         VARCHAR(10)   NOT NULL DEFAULT '',   -- delivery: swiggy | zomato | other
  partner_ref     VARCHAR(40)   NOT NULL DEFAULT '',
  customer_name   VARCHAR(60)   NOT NULL DEFAULT '',
  customer_phone  VARCHAR(20)   NOT NULL DEFAULT '',
  status          VARCHAR(10)   NOT NULL DEFAULT 'open',  -- open | billed | paid | cancelled
  subtotal        DECIMAL(12,2) NOT NULL DEFAULT 0,
  tax_pct         DECIMAL(5,2)  NOT NULL DEFAULT 5,
  tax             DECIMAL(12,2) NOT NULL DEFAULT 0,
  discount        DECIMAL(12,2) NOT NULL DEFAULT 0,
  total           DECIMAL(12,2) NOT NULL DEFAULT 0,
  bill_no         VARCHAR(20)   NOT NULL DEFAULT '',
  pay_mode        VARCHAR(12)   NOT NULL DEFAULT '',
  created_at      DATETIME      NOT NULL,
  created_by      BIGINT        NOT NULL DEFAULT 0,
  created_by_name VARCHAR(60)   NOT NULL DEFAULT '',
  billed_at       DATETIME      NULL,
  closed_at       DATETIME      NULL,
  PRIMARY KEY (id),
  KEY idx_orders_status (status, type),
  KEY idx_orders_table (table_id, status),
  KEY idx_orders_closed (closed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_lines (
  order_id  BIGINT        NOT NULL,
  line_no   INT           NOT NULL,
  item_id   BIGINT        NULL,
  name      VARCHAR(60)   NOT NULL,
  qty       INT           NOT NULL DEFAULT 1,
  price     DECIMAL(10,2) NOT NULL DEFAULT 0,
  amount    DECIMAL(12,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (order_id, line_no),
  CONSTRAINT fk_lines_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payments (
  id        BIGINT        NOT NULL AUTO_INCREMENT,
  order_id  BIGINT        NOT NULL,
  amount    DECIMAL(12,2) NOT NULL DEFAULT 0,
  mode      VARCHAR(12)   NOT NULL DEFAULT 'cash',
  paid_at   DATETIME      NOT NULL,
  by_name   VARCHAR(60)   NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  KEY idx_pay_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
