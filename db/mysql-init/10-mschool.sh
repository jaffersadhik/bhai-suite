#!/bin/sh
# docker-compose.mysql.yaml: first start of an empty MySQL -> also create schema "mschool" for the same app user
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS mschool CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL PRIVILEGES ON mschool.* TO '$MYSQL_USER'@'%'; FLUSH PRIVILEGES;"
