package com.suite.security;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Where failed-login counters and IP blocks are kept (one table pair per application database). */
public interface Store {
    /** counters of one IP for one day */
    record Counts(int fails, int captchaFails) {
    }

    /** a block (active or already lifted) */
    record Block(String ip, LocalDateTime blockedAt, String reason, int fails, String lastUser, boolean active, LocalDateTime unblockedAt, String unblockedBy) {
    }

    void init();

    /** adds one failure to the counters of (ip, day) and returns the new totals */
    Counts bump(String ip, LocalDate day, boolean captcha, LocalDateTime now);

    /** the ACTIVE block of this key, or null */
    Block findBlock(String ip);

    void block(String ip, String reason, int fails, String lastUser, LocalDateTime now);

    /** lifts the block of exactly this key; false when there was none */
    boolean unblock(String ip, String by, LocalDateTime now);

    /** newest first: active blocks first, then recently lifted ones (max 100 rows) */
    List<Block> blocks();

    /** forget today's counters of this IP (after an unblock) */
    void clearDay(String ip, LocalDate day);

    void purgeBefore(LocalDate day);
}
