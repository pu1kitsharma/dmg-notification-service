package com.dmg.notify.common;

public final class Text {
    private Text() {}

    /** Cuts free text (provider errors, audit reasons) to a column limit; a too-long value must never fail a transaction. */
    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
