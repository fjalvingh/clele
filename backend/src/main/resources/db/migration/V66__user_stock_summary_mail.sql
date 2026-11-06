-- When this user was last sent the weekly stock summary mail. Null means never, so the next check
-- mails them. Held per user so that a restart neither repeats nor skips a mail, and a send that
-- failed for one recipient does not count as done.
ALTER TABLE app_user ADD COLUMN last_stock_summary_at TIMESTAMP;
