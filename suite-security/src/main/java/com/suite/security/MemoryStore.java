package com.suite.security;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory store (self-check, or an app that has no database). Not shared between servers, lost on restart. */
public final class MemoryStore implements Store {
    private final Map<String, int[]> counts = new HashMap<>();
    private final Map<String, Block> blocks = new HashMap<>();

    @Override public void init() { }

    @Override public synchronized Counts bump(String ip, LocalDate day, boolean captcha, LocalDateTime now) {
        int[] c = counts.computeIfAbsent(ip + "|" + day, k -> new int[2]);
        c[captcha ? 1 : 0]++;
        return new Counts(c[0], c[1]);
    }

    @Override public synchronized Block findBlock(String ip) {
        Block b = blocks.get(ip);
        return b != null && b.active() ? b : null;
    }

    @Override public synchronized void block(String ip, String reason, int fails, String lastUser, LocalDateTime now) {
        blocks.put(ip, new Block(ip, now, reason, fails, lastUser, true, null, null));
    }

    @Override public synchronized boolean unblock(String ip, String by, LocalDateTime now) {
        Block b = blocks.get(ip);
        if (b == null || !b.active()) return false;
        blocks.put(ip, new Block(b.ip(), b.blockedAt(), b.reason(), b.fails(), b.lastUser(), false, now, by));
        return true;
    }

    @Override public synchronized List<Block> blocks() {
        List<Block> l = new ArrayList<>(blocks.values());
        l.sort(Comparator.comparing(Block::active).reversed().thenComparing(Block::blockedAt, Comparator.reverseOrder()));
        return l.size() > 100 ? new ArrayList<>(l.subList(0, 100)) : l;
    }

    @Override public synchronized void clearDay(String ip, LocalDate day) { counts.remove(ip + "|" + day); }

    @Override public synchronized void purgeBefore(LocalDate day) { counts.keySet().removeIf(k -> LocalDate.parse(k.substring(k.indexOf('|') + 1)).isBefore(day)); }
}
