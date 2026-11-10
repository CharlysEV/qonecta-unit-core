package com.headqlink.unit.companion.cloud;

/** El error para el log: tipo y mensaje corto, sin URLs con datos. */
final class Http {
    private Http() {
    }

    static String safeError(Throwable e) {
        if (e == null) return "?";
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        m = m.replaceAll("https?://\\S+", "<url>");
        if (m.length() > 160) m = m.substring(0, 160);
        return e.getClass().getSimpleName() + ": " + m;
    }
}
