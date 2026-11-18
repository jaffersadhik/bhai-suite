-- Manual alternative (external / already running MySQL): run once as root, replace bmb with your DB_USER.
-- MSchool then uses the SAME user and password as Bhai Mobile Billing; the tables are created by the app at start-up.
CREATE DATABASE IF NOT EXISTS mschool CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON mschool.* TO 'bmb'@'%';
FLUSH PRIVILEGES;
