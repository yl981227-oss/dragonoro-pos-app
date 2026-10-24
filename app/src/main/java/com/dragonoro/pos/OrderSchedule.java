package com.dragonoro.pos;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java scheduling policy; never anchors a relative date to the polling day. */
public final class OrderSchedule {
    private static final TimeZone SANTIAGO = TimeZone.getTimeZone("America/Santiago");
    private static final int OPEN_MINUTE = 12 * 60 + 20;
    private static final int LAST_PICKUP_MINUTE = 22 * 60 + 50;
    private static final long MINUTE = 60_000L;
    private static final Pattern EXPLICIT_DATE = Pattern.compile("\\[fecha (\\d{4}-\\d{2}-\\d{2}), America/Santiago\\]");
    private static final Pattern TIME = Pattern.compile("(?:a las|para las)\\s+(\\d{1,2}):(\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE = Pattern.compile("(hoy|ma.ana)\\s+a\\s+las", Pattern.CASE_INSENSITIVE);

    public enum Action { READY, WAIT, REVIEW }

    public static final class Decision {
        public final Action action;
        public final String reason;
        public final long printAt;
        public final String pickupDate;

        private Decision(Action action, String reason, long printAt, String pickupDate) {
            this.action = action;
            this.reason = reason;
            this.printAt = printAt;
            this.pickupDate = pickupDate;
        }
    }

    private static Decision review(String reason) {
        return new Decision(Action.REVIEW, reason, 0, "");
    }

    private static Calendar calendar(long instant) {
        Calendar c = Calendar.getInstance(SANTIAGO, Locale.US);
        c.setTimeInMillis(instant);
        return c;
    }

    private static String dateKey(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static long parseCreatedAt(String value) throws ParseException {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})")) {
            throw new ParseException("Missing creation date", 0);
        }
        // Sub-second precision is irrelevant to a minute-based kitchen schedule.
        String clean = value.replaceFirst("\\.\\d+(?=Z|[+-])", "");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        format.setLenient(false);
        return format.parse(clean).getTime();
    }

    public static Decision evaluate(String timing, String createdAt, long now) {
        final long created;
        try { created = parseCreatedAt(createdAt); }
        catch (Exception error) { return review("Fecha de creación inválida. Verifica el pedido."); }
        if (created > now + 5 * MINUTE) return review("La fecha del pedido está en el futuro. Revisa el reloj.");
        String text = timing == null ? "" : timing;
        boolean scheduled = text.toUpperCase(Locale.US).contains("PROGRAMADO");
        if (!scheduled) {
            if (now - created > 24 * 60 * MINUTE) return review("Pedido antiguo pendiente. Confirma si aún debe prepararse.");
            return new Decision(Action.READY, "", now, "");
        }

        Matcher timeMatch = TIME.matcher(text);
        if (!timeMatch.find()) return review("Reserva sin hora válida. Confirma la hora con el cliente.");
        int hour = Integer.parseInt(timeMatch.group(1));
        int minute = Integer.parseInt(timeMatch.group(2));
        int pickupMinute = hour * 60 + minute;
        if (hour > 23 || minute > 59 || pickupMinute < OPEN_MINUTE || pickupMinute > LAST_PICKUP_MINUTE) {
            return review("Hora de retiro fuera del horario configurado.");
        }

        Calendar creationDay = calendar(created);
        int creationMinute = creationDay.get(Calendar.HOUR_OF_DAY) * 60 + creationDay.get(Calendar.MINUTE);
        Matcher explicitMatch = EXPLICIT_DATE.matcher(text);
        String date;
        if (explicitMatch.find()) {
            date = explicitMatch.group(1);
        } else {
            if (text.contains("[fecha")) return review("Fecha explícita inválida. Confirma la reserva.");
            Matcher relative = RELATIVE.matcher(text);
            if (relative.find()) {
                boolean tomorrow = relative.group(1).toLowerCase(Locale.US).startsWith("ma");
                // Old frontend marked every closed hour as 'tomorrow', including after midnight.
                // It is unsafe to guess intent for already persisted legacy reservations.
                if (tomorrow && creationMinute < OPEN_MINUTE) {
                    return review("Reserva antigua ambigua de madrugada. Confirma la fecha de retiro.");
                }
                if (tomorrow) creationDay.add(Calendar.DAY_OF_MONTH, 1);
                date = dateKey(creationDay);
            } else if (text.contains("Programado para las")) {
                if (creationMinute >= 23 * 60) creationDay.add(Calendar.DAY_OF_MONTH, 1);
                else if (creationMinute >= pickupMinute) return review("Reserva antigua con hora anterior a su creación.");
                date = dateKey(creationDay);
            } else {
                return review("No se reconoce la fecha de reserva. Confírmala antes de imprimir.");
            }
        }

        final long pickupAt;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            format.setTimeZone(SANTIAGO);
            format.setLenient(false);
            Date value = format.parse(date + String.format(Locale.US, " %02d:%02d", hour, minute));
            pickupAt = value.getTime();
            if (!format.format(value).equals(date + String.format(Locale.US, " %02d:%02d", hour, minute))) {
                return review("Fecha de reserva inválida.");
            }
        } catch (Exception error) { return review("Fecha de reserva inválida."); }
        if (pickupAt < created - MINUTE) return review("La reserva es anterior a la creación del pedido.");
        if (now > pickupAt + 60 * MINUTE) return review("Reserva vencida. Confirma con el cliente antes de prepararla.");

        Calendar opening = calendar(pickupAt);
        opening.set(Calendar.HOUR_OF_DAY, 12);
        opening.set(Calendar.MINUTE, 20);
        opening.set(Calendar.SECOND, 0);
        opening.set(Calendar.MILLISECOND, 0);
        long printAt = Math.max(pickupAt - 30 * MINUTE, opening.getTimeInMillis());
        return new Decision(now >= printAt ? Action.READY : Action.WAIT, "", printAt, date);
    }

    private OrderSchedule() {}
}
