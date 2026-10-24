package com.dragonoro.pos;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/** Run with javac/java; uses actual production policy classes, no Android or network. */
public final class QueueRegression {
    private static int passed = 0;
    private static int failed = 0;

    private static final class MemoryStore implements PrintJournal.Store {
        final Map<Long, String> values = new HashMap<>();
        boolean failWrites;
        public String read(long id) { return values.get(id); }
        public boolean write(long id, String value) {
            if (failWrites) return false;
            values.put(id, value);
            return true;
        }
    }

    private static long at(String iso) { return Instant.parse(iso).toEpochMilli(); }
    private static String scheduled(String date, String time) {
        return "Programado: hoy a las " + time + " hrs [fecha " + date + ", America/Santiago] (PEDIDO PROGRAMADO)";
    }
    private static OrderSchedule.Decision eval(String timing, String created, String now) {
        return OrderSchedule.evaluate(timing, created, at(now));
    }
    private static void require(boolean value) { if (!value) throw new AssertionError("Requirement failed"); }
    private static void expect(OrderSchedule.Action value, OrderSchedule.Decision decision) {
        if (decision.action != value) throw new AssertionError("Expected " + value + ", got " + decision.action + ": " + decision.reason);
    }
    private static void test(String name, Runnable body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable error) { failed++; System.out.println("FAIL " + name + " :: " + error); }
    }

    public static void main(String[] args) {
        test("Immediate new order is ready", () -> expect(OrderSchedule.Action.READY,
                eval("Inmediato", "2026-10-23T18:00:00Z", "2026-10-23T18:00:05Z")));
        test("Old pending immediate order requires review", () -> expect(OrderSchedule.Action.REVIEW,
                eval("Inmediato", "2026-10-20T18:00:00Z", "2026-10-23T18:00:05Z")));
        test("Missing creation timestamp requires review", () -> expect(OrderSchedule.Action.REVIEW,
                eval("Inmediato", "", "2026-10-23T18:00:00Z")));
        test("Future creation timestamp requires clock review", () -> expect(OrderSchedule.Action.REVIEW,
                eval("Inmediato", "2026-10-24T18:00:00Z", "2026-10-23T18:00:00Z")));
        test("13:30 pickup waits until 13:00 Santiago", () -> expect(OrderSchedule.Action.WAIT,
                eval(scheduled("2026-10-23", "13:30"), "2026-10-23T05:00:00Z", "2026-10-23T15:59:59Z")));
        test("13:30 pickup releases at 13:00", () -> expect(OrderSchedule.Action.READY,
                eval(scheduled("2026-10-23", "13:30"), "2026-10-23T05:00:00Z", "2026-10-23T16:00:00Z")));
        test("12:30 pickup cannot release before 12:20 opening", () -> expect(OrderSchedule.Action.WAIT,
                eval(scheduled("2026-10-23", "12:30"), "2026-10-23T05:00:00Z", "2026-10-23T15:19:59Z")));
        test("12:30 pickup releases at 12:20 opening", () -> expect(OrderSchedule.Action.READY,
                eval(scheduled("2026-10-23", "12:30"), "2026-10-23T05:00:00Z", "2026-10-23T15:20:00Z")));
        test("Legacy evening tomorrow anchors to creation date", () -> {
            String line = "Programado: mañana a las 13:30 hrs (PEDIDO PROGRAMADO)";
            OrderSchedule.Decision d = eval(line, "2026-10-24T02:30:00Z", "2026-10-24T16:00:00Z");
            expect(OrderSchedule.Action.READY, d); require(d.pickupDate.equals("2026-10-24"));
        });
        test("Legacy tomorrow never drifts on following polling day", () -> {
            String line = "Programado: mañana a las 13:30 hrs (PEDIDO PROGRAMADO)";
            expect(OrderSchedule.Action.REVIEW, eval(line, "2026-10-24T02:30:00Z", "2026-10-25T16:00:00Z"));
        });
        test("Ambiguous old midnight reservation requires human confirmation", () -> expect(OrderSchedule.Action.REVIEW,
                eval("Programado: mañana a las 13:30 hrs (PEDIDO PROGRAMADO)", "2026-10-23T05:00:00Z", "2026-10-23T16:00:00Z")));
        test("Explicit date fixes midnight legacy ambiguity", () -> expect(OrderSchedule.Action.READY,
                eval(scheduled("2026-10-23", "13:30"), "2026-10-23T05:00:00Z", "2026-10-23T16:00:00Z")));
        test("Legacy hoy stays on original creation day", () -> {
            OrderSchedule.Decision d = eval("Programado: hoy a las 16:00 hrs (PEDIDO PROGRAMADO)",
                    "2026-10-23T18:00:00Z", "2026-10-23T18:30:00Z");
            expect(OrderSchedule.Action.READY, d); require(d.pickupDate.equals("2026-10-23"));
        });
        test("Legacy earliest format supported if unambiguous", () -> expect(OrderSchedule.Action.READY,
                eval("Programado para las 16:00 hrs (PEDIDO PROGRAMADO)", "2026-10-23T18:00:00Z", "2026-10-23T18:30:00Z")));
        test("Expired reservation requires review not kitchen reprint", () -> expect(OrderSchedule.Action.REVIEW,
                eval(scheduled("2026-10-23", "12:30"), "2026-10-23T05:00:00Z", "2026-10-23T18:00:00Z")));
        test("Invalid time does not print immediately", () -> expect(OrderSchedule.Action.REVIEW,
                eval(scheduled("2026-10-23", "12:99"), "2026-10-23T05:00:00Z", "2026-10-23T15:20:00Z")));
        test("Outside business hours requires review", () -> expect(OrderSchedule.Action.REVIEW,
                eval(scheduled("2026-10-23", "11:30"), "2026-10-23T05:00:00Z", "2026-10-23T15:20:00Z")));
        test("Invalid date requires review", () -> expect(OrderSchedule.Action.REVIEW,
                eval(scheduled("2026-02-30", "12:30"), "2026-02-20T05:00:00Z", "2026-02-20T15:20:00Z")));
        test("Explicit date earlier than creation requires review", () -> expect(OrderSchedule.Action.REVIEW,
                eval(scheduled("2026-10-22", "12:30"), "2026-10-23T05:00:00Z", "2026-10-23T15:20:00Z")));
        test("Missing scheduled time requires review", () -> expect(OrderSchedule.Action.REVIEW,
                eval("PEDIDO PROGRAMADO", "2026-10-23T05:00:00Z", "2026-10-23T15:20:00Z")));
        test("Host Asia Shanghai timezone cannot shift release time", () -> {
            TimeZone old = TimeZone.getDefault();
            try { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
                expect(OrderSchedule.Action.READY, eval(scheduled("2026-10-23", "12:30"),
                        "2026-10-23T05:00:00Z", "2026-10-23T15:20:00Z"));
            } finally { TimeZone.setDefault(old); }
        });
        test("Winter UTC-4 opening time", () -> expect(OrderSchedule.Action.READY,
                eval(scheduled("2026-07-15", "12:30"), "2026-07-15T05:00:00Z", "2026-07-15T16:20:00Z")));
        test("Summer UTC-3 opening time", () -> expect(OrderSchedule.Action.READY,
                eval(scheduled("2026-01-15", "12:30"), "2026-01-15T05:00:00Z", "2026-01-15T15:20:00Z")));
        test("Creation timestamp offset and microseconds supported", () -> expect(OrderSchedule.Action.READY,
                eval(scheduled("2026-10-23", "12:30"), "2026-10-23T02:00:00.123456-03:00", "2026-10-23T15:20:00Z")));
        test("Creation invalid calendar date rejected", () -> expect(OrderSchedule.Action.REVIEW,
                eval("Inmediato", "2026-02-30T05:00:00Z", "2026-03-01T15:20:00Z")));
        test("Cross year reservation anchored to fixed date", () -> {
            OrderSchedule.Decision d = eval("Programado: mañana a las 12:30 hrs (PEDIDO PROGRAMADO)",
                    "2027-01-01T02:30:00Z", "2027-01-01T15:20:00Z");
            expect(OrderSchedule.Action.READY, d); require(d.pickupDate.equals("2027-01-01"));
        });
        test("New order can enter dispatch journal", () -> { PrintJournal j = new PrintJournal(new MemoryStore()); require(j.begin(1)); });
        test("Journal failure prevents dispatch begin", () -> { MemoryStore s = new MemoryStore(); s.failWrites=true; require(!new PrintJournal(s).begin(1)); });
        test("Order id must be positive", () -> { require(!new PrintJournal(new MemoryStore()).begin(0)); });
        test("Successful dispatch persists SENT", () -> { PrintJournal j=new PrintJournal(new MemoryStore()); j.begin(1); require(j.sent(1)); require(j.state(1)==PrintJournal.State.SENT); });
        test("SENT cannot dispatch again while PATCH fails", () -> { PrintJournal j=new PrintJournal(new MemoryStore()); j.begin(1); j.sent(1); require(!j.begin(1)); });
        test("Restart preserves SENT, not a new print", () -> { MemoryStore s=new MemoryStore(); PrintJournal j=new PrintJournal(s);j.begin(1);j.sent(1); PrintJournal restarted=new PrintJournal(s);require(restarted.state(1)==PrintJournal.State.SENT);require(!restarted.begin(1)); });
        test("Restart detects interrupted SENDING", () -> { MemoryStore s=new MemoryStore();new PrintJournal(s).begin(1);PrintJournal j=new PrintJournal(s);require(j.state(1)==PrintJournal.State.SENDING);require(!j.begin(1)); });
        test("Uncertain write does not auto-retry", () -> { PrintJournal j=new PrintJournal(new MemoryStore());j.begin(1);j.uncertain(1);require(!j.begin(1)); });
        test("Paper exists confirmation changes uncertain to SENT", () -> { PrintJournal j=new PrintJournal(new MemoryStore());j.uncertain(1);require(j.resolve(1,true));require(j.state(1)==PrintJournal.State.SENT); });
        test("Operator authorized retry uses APPROVED", () -> { PrintJournal j=new PrintJournal(new MemoryStore());j.uncertain(1);require(j.resolve(1,false));require(j.state(1)==PrintJournal.State.APPROVED);require(j.begin(1)); });
        test("Active sending cannot be resolved mid-write", () -> { PrintJournal j=new PrintJournal(new MemoryStore());j.begin(1);require(!j.resolve(1,false)); });
        test("Review order remains quarantined", () -> { PrintJournal j=new PrintJournal(new MemoryStore());j.review(1);require(!j.begin(1)); });
        test("Operator may authorize legacy date review", () -> { PrintJournal j=new PrintJournal(new MemoryStore());j.review(1);require(j.resolve(1,false));require(j.begin(1)); });
        test("Corrupt journal is not treated as brand-new order", () -> { MemoryStore s=new MemoryStore();s.write(1,"bad-data");PrintJournal j=new PrintJournal(s);require(j.state(1)==PrintJournal.State.UNCERTAIN);require(!j.begin(1)); });
        test("SENT -> SYNCED is durable", () -> { MemoryStore s=new MemoryStore();PrintJournal j=new PrintJournal(s);j.begin(1);j.sent(1);require(j.synced(1));require(new PrintJournal(s).state(1)==PrintJournal.State.SYNCED); });
        test("Completed journal cannot be resolved for repeat", () -> { PrintJournal j=new PrintJournal(new MemoryStore());j.begin(1);j.sent(1);j.synced(1);require(!j.resolve(1,false));require(!j.begin(1)); });
        test("Post-write journal failure leaves SENDING to block retry", () -> { MemoryStore s=new MemoryStore();PrintJournal j=new PrintJournal(s);j.begin(1);s.failWrites=true;require(!j.sent(1));require(j.state(1)==PrintJournal.State.SENDING);require(!j.begin(1)); });
        test("Pending status sync failure preserves SENT", () -> { MemoryStore s=new MemoryStore();PrintJournal j=new PrintJournal(s);j.begin(1);j.sent(1);s.failWrites=true;require(!j.synced(1));require(j.state(1)==PrintJournal.State.SENT); });
        test("Different order IDs dispatch independently", () -> { PrintJournal j=new PrintJournal(new MemoryStore());j.begin(1);j.sent(1);require(j.begin(2)); });
        test("Malformed explicit date does not fall back to relative day", () -> expect(OrderSchedule.Action.REVIEW,
                eval("Programado: hoy a las 13:30 [fecha broken, America/Santiago] (PEDIDO PROGRAMADO)",
                        "2026-10-23T05:00:00Z", "2026-10-23T16:00:00Z")));
        test("Unreadable local journal blocks automatic dispatch", () -> {
            PrintJournal j = new PrintJournal(new PrintJournal.Store() {
                public String read(long id) { throw new ClassCastException("Corrupt preference type"); }
                public boolean write(long id, String value) { return true; }
            });
            require(j.state(1) == PrintJournal.State.UNCERTAIN); require(!j.begin(1));
        });
        test("Activity and service journal instances share transition lock", () -> {
            MemoryStore store = new MemoryStore();
            PrintJournal a = new PrintJournal(store), b = new PrintJournal(store);
            java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            Runnable attemptA = () -> { try { start.await(); if (a.begin(1)) winners.incrementAndGet(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); } };
            Runnable attemptB = () -> { try { start.await(); if (b.begin(1)) winners.incrementAndGet(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); } };
            Thread t1 = new Thread(attemptA), t2 = new Thread(attemptB);
            t1.start(); t2.start(); start.countDown();
            try { t1.join(); t2.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
            require(winners.get() == 1);
        });
        System.out.println("RESULT " + passed + "/" + (passed+failed) + " passed; offline actual production Java policy tests.");
        if (failed > 0) System.exit(1);
    }
}
