-- Run once as MySQL root:   mysql -u root -p < db/create-database.sql
-- Creates the database and an application user. Change the password before running.
-- The tables themselves are created by the application at startup
-- (src/main/resources/db/schema.sql). You can also create them by hand:
--   mysql -u bmb -p bmb < src/main/resources/db/schema.sql

CREATE DATABASE IF NOT EXISTS bmb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'bmb'@'localhost' IDENTIFIED BY 'Change_Me_123';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON bmb.* TO 'bmb'@'localhost';
FLUSH PRIVILEGES;
