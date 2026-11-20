-- MSchool schema (MySQL 8). Runs on every start, never alters existing tables.
-- All money is whole rupees (BIGINT). Dates use the institutions local date.

CREATE TABLE IF NOT EXISTS institutions (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  kind VARCHAR(12) NOT NULL DEFAULT 'school',
  phone VARCHAR(20) NULL,
  address VARCHAR(255) NULL,
  weekly_off TINYINT NOT NULL DEFAULT 7,
  receipt_seq INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(40) NOT NULL,
  pass_salt VARCHAR(32) NOT NULL,
  pass_hash VARCHAR(64) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  user_type VARCHAR(12) NOT NULL,
  inst_id BIGINT NULL,
  staff_id BIGINT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uq_users_name (username),
  KEY idx_users_inst (inst_id),
  CONSTRAINT fk_users_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS auth_tokens (
  token VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  inst_id BIGINT NULL,
  expires_at DATETIME NOT NULL,
  KEY idx_tokens_user (user_id),
  CONSTRAINT fk_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS academic_years (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  name VARCHAR(20) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  is_current TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_year_name (inst_id, name),
  CONSTRAINT fk_year_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A class is a standard (school) or a course-year (college / university). next_class_id = where a passed student goes, NULL = final year.
CREATE TABLE IF NOT EXISTS classes (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  name VARCHAR(60) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  next_class_id BIGINT NULL,
  UNIQUE KEY uq_class_name (inst_id, name),
  CONSTRAINT fk_class_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rooms (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  name VARCHAR(40) NOT NULL,
  building VARCHAR(60) NULL,
  floor_no VARCHAR(20) NULL,
  capacity INT NOT NULL DEFAULT 0,
  kind VARCHAR(20) NOT NULL DEFAULT 'classroom',
  status VARCHAR(12) NOT NULL DEFAULT 'active',
  notes VARCHAR(255) NULL,
  UNIQUE KEY uq_room_name (inst_id, name),
  CONSTRAINT fk_room_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS room_maintenance (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  room_id BIGINT NOT NULL,
  issue VARCHAR(255) NOT NULL,
  reported_on DATE NOT NULL,
  cost BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(8) NOT NULL DEFAULT 'open',
  done_on DATE NULL,
  note VARCHAR(255) NULL,
  KEY idx_maint_room (room_id, status),
  CONSTRAINT fk_maint_room FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS staff (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  emp_no VARCHAR(20) NOT NULL,
  name VARCHAR(80) NOT NULL,
  kind VARCHAR(10) NOT NULL DEFAULT 'teacher',
  designation VARCHAR(60) NULL,
  subjects VARCHAR(120) NULL,
  phone VARCHAR(20) NULL,
  address VARCHAR(255) NULL,
  joined_on DATE NULL,
  basic BIGINT NOT NULL DEFAULT 0,
  allowance BIGINT NOT NULL DEFAULT 0,
  deduction BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(8) NOT NULL DEFAULT 'active',
  notes VARCHAR(255) NULL,
  UNIQUE KEY uq_staff_emp (inst_id, emp_no),
  KEY idx_staff_inst (inst_id, status),
  CONSTRAINT fk_staff_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sections (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  class_id BIGINT NOT NULL,
  name VARCHAR(20) NOT NULL,
  room_id BIGINT NULL,
  class_teacher_id BIGINT NULL,
  UNIQUE KEY uq_section (class_id, name),
  KEY idx_section_inst (inst_id),
  CONSTRAINT fk_section_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS students (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  adm_no VARCHAR(20) NOT NULL,
  name VARCHAR(80) NOT NULL,
  gender VARCHAR(1) NULL,
  dob DATE NULL,
  father_name VARCHAR(80) NULL,
  mother_name VARCHAR(80) NULL,
  phone VARCHAR(20) NULL,
  phone2 VARCHAR(20) NULL,
  address VARCHAR(255) NULL,
  joined_on DATE NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'active',
  notes VARCHAR(255) NULL,
  UNIQUE KEY uq_student_adm (inst_id, adm_no),
  KEY idx_student_inst (inst_id, status),
  CONSTRAINT fk_student_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Which class a student is in, per academic year. A promotion creates the next years row.
CREATE TABLE IF NOT EXISTS enrollments (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  year_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  class_id BIGINT NOT NULL,
  section_id BIGINT NOT NULL,
  roll_no INT NOT NULL DEFAULT 0,
  result VARCHAR(8) NULL,
  UNIQUE KEY uq_enroll (year_id, student_id),
  KEY idx_enroll_section (year_id, section_id),
  KEY idx_enroll_student (student_id),
  CONSTRAINT fk_enroll_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS student_attendance (
  att_date DATE NOT NULL,
  student_id BIGINT NOT NULL,
  inst_id BIGINT NOT NULL,
  section_id BIGINT NOT NULL,
  status CHAR(1) NOT NULL,
  marked_by VARCHAR(80) NULL,
  PRIMARY KEY (att_date, student_id),
  KEY idx_satt_student (student_id, att_date),
  KEY idx_satt_section (inst_id, section_id, att_date),
  CONSTRAINT fk_satt_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS staff_attendance (
  att_date DATE NOT NULL,
  staff_id BIGINT NOT NULL,
  inst_id BIGINT NOT NULL,
  status CHAR(1) NOT NULL,
  note VARCHAR(120) NULL,
  PRIMARY KEY (att_date, staff_id),
  KEY idx_tatt_staff (staff_id, att_date),
  KEY idx_tatt_inst (inst_id, att_date),
  CONSTRAINT fk_tatt_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fee_heads (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  name VARCHAR(60) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_head (inst_id, name),
  CONSTRAINT fk_head_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fee_structure (
  inst_id BIGINT NOT NULL,
  year_id BIGINT NOT NULL,
  class_id BIGINT NOT NULL,
  head_id BIGINT NOT NULL,
  amount BIGINT NOT NULL,
  PRIMARY KEY (year_id, class_id, head_id),
  KEY idx_fs_inst (inst_id),
  CONSTRAINT fk_fs_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fee_concessions (
  inst_id BIGINT NOT NULL,
  year_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  head_id BIGINT NOT NULL,
  amount BIGINT NOT NULL,
  PRIMARY KEY (year_id, student_id, head_id),
  KEY idx_fc_inst (inst_id),
  CONSTRAINT fk_fc_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fee_receipts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  receipt_seq INT NOT NULL,
  receipt_no VARCHAR(20) NOT NULL,
  year_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  total BIGINT NOT NULL,
  pay_mode VARCHAR(12) NOT NULL,
  note VARCHAR(255) NULL,
  created_by VARCHAR(80) NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'ok',
  cancelled_at DATETIME NULL,
  cancel_reason VARCHAR(255) NULL,
  UNIQUE KEY uq_receipt_seq (inst_id, receipt_seq),
  KEY idx_receipt_student (student_id, year_id),
  KEY idx_receipt_date (inst_id, created_at),
  CONSTRAINT fk_receipt_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fee_receipt_lines (
  receipt_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  head_id BIGINT NOT NULL,
  head_name VARCHAR(60) NOT NULL,
  amount BIGINT NOT NULL,
  PRIMARY KEY (receipt_id, line_no),
  KEY idx_frl_head (head_id),
  CONSTRAINT fk_frl_receipt FOREIGN KEY (receipt_id) REFERENCES fee_receipts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payroll (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  inst_id BIGINT NOT NULL,
  pay_month CHAR(7) NOT NULL,
  staff_id BIGINT NOT NULL,
  staff_name VARCHAR(80) NOT NULL,
  designation VARCHAR(60) NULL,
  working_days INT NOT NULL,
  lop_days DECIMAL(5,1) NOT NULL DEFAULT 0,
  basic BIGINT NOT NULL,
  allowance BIGINT NOT NULL,
  deduction BIGINT NOT NULL,
  bonus BIGINT NOT NULL DEFAULT 0,
  other_deduct BIGINT NOT NULL DEFAULT 0,
  lop_amount BIGINT NOT NULL DEFAULT 0,
  net BIGINT NOT NULL,
  status VARCHAR(8) NOT NULL DEFAULT 'draft',
  paid_on DATE NULL,
  pay_mode VARCHAR(12) NULL,
  note VARCHAR(255) NULL,
  UNIQUE KEY uq_payroll (inst_id, pay_month, staff_id),
  CONSTRAINT fk_payroll_inst FOREIGN KEY (inst_id) REFERENCES institutions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- translations : online translations of screen phrases / names (Tamil source text -> language), shared by all users
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS translations (
  src_hash CHAR(32)     NOT NULL,                  -- md5 of the Tamil source text
  lang     VARCHAR(8)   NOT NULL,
  src      VARCHAR(320) NOT NULL,
  txt      VARCHAR(640) NOT NULL,
  PRIMARY KEY (src_hash, lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
