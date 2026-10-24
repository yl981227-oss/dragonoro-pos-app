package com.dragonoro.pos;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences.commit() is intentional: persist before writing to a printer socket. */
final class PrintJournalStore implements PrintJournal.Store {
    static final String PREFS = "print_journal_v1";
    private final SharedPreferences prefs;

    PrintJournalStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public String read(long id) { return prefs.getString("state_" + id, ""); }
    @Override public boolean write(long id, String state) {
        return prefs.edit().putString("state_" + id, state).commit();
    }

    void remember(long id, String code) {
        // Only an order identifier, never a full ticket or client data.
        String safeCode = code == null ? "" : code;
        if (safeCode.length() > 128) safeCode = safeCode.substring(0, 128);
        if (!safeCode.equals(code(id))) prefs.edit().putString("code_" + id, safeCode).apply();
    }

    String code(long id) {
        try { return prefs.getString("code_" + id, "Pedido " + id); }
        catch (ClassCastException error) { return "Pedido " + id; }
    }
    SharedPreferences preferences() { return prefs; }
}
