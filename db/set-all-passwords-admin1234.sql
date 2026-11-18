-- Sets the password of EVERY user (admin and all company users) to: admin1234
-- (demo phase). Run:  docker compose exec -T mysql mysql -u root -p bmb < db/set-all-passwords-admin1234.sql
UPDATE users SET pass_salt = 'Nw06tmGRWXm+a/GngbsSBA==', pass_hash = 'wtiPnAuZHf41PfBVmi7aeXz5ufYBHigoKDD7/llonN0=', must_change = 0, updated_at = NOW();
SELECT username, user_type FROM users ORDER BY user_type, username;
