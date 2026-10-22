package com.dragonoro.pos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds a 80mm ESC/POS ticket from a Supabase order row.
 * Mirrors the layout used by admin.html so printed tickets look identical
 * whether they come from the web layer or from the background service.
 */
public class EscPosBuilder {

    private static final int ESC = 0x1B;
    private static final int GS = 0x1D;
    private static final int FS = 0x1C;
    private static final int WIDTH = 48;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private void cmd(int... b) {
        for (int x : b) out.write(x);
    }

    private static String clean(String s) {
        if (s == null) return "";
        String r = s
                .replace('\u00E1', 'a').replace('\u00E9', 'e').replace('\u00ED', 'i')
                .replace('\u00F3', 'o').replace('\u00FA', 'u')
                .replace('\u00C1', 'A').replace('\u00C9', 'E').replace('\u00CD', 'I')
                .replace('\u00D3', 'O').replace('\u00DA', 'U')
                .replace('\u00F1', 'n').replace('\u00D1', 'N')
                .replace('\u00FC', 'u').replace('\u00DC', 'U')
                .replace('\u00D7', 'x').replace('\u2794', '>');
        return r.replaceAll("[^\\x20-\\x7E\\n]", "");
    }

    private void text(String s) {
        byte[] b = clean(s).getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static String lr(String left, String right) {
        String a = clean(left);
        String b = clean(right);
        int spaces = Math.max(1, WIDTH - a.length() - b.length());
        StringBuilder sb = new StringBuilder(a);
        for (int i = 0; i < spaces; i++) sb.append(' ');
        sb.append(b);
        return sb.toString();
    }

    private static String money(double v) {
        return "$" + String.format(Locale.US, "%.0f", v);
    }

    private static String fecha(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso.substring(0, 19));
            SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("America/Santiago"));
            return fmt.format(d);
        } catch (Exception e) {
            return iso == null ? "" : iso;
        }
    }

    public byte[] build(JSONObject o) {
        cmd(ESC, 0x40);
        cmd(FS, 0x2E);
        cmd(ESC, 0x52, 0x00);
        cmd(ESC, 0x74, 0x00);

        cmd(ESC, 0x61, 0x01);
        cmd(ESC, 0x45, 0x01);
        cmd(GS, 0x21, 0x11);
        text("CHINA IDEAL\n");
        cmd(GS, 0x21, 0x00);
        cmd(ESC, 0x45, 0x00);
        text("Comida China Cantonesa & Sushi\n");
        text("Camino de la Vina 1461, Huechuraba\n");
        text("================================================\n");

        cmd(ESC, 0x61, 0x00);
        cmd(ESC, 0x45, 0x01);
        text("PEDIDO: " + o.optString("order_code") + "\n");
        cmd(ESC, 0x45, 0x00);
        text("FECHA:  " + fecha(o.optString("created_at")) + "\n");
        text("------------------------------------------------\n");

        cmd(ESC, 0x45, 0x01);
        cmd(GS, 0x21, 0x01);
        text("SERVICIO: " + o.optString("service_type").toUpperCase(Locale.US) + "\n");
        text("HORARIO:  " + o.optString("timing_info") + "\n");
        cmd(GS, 0x21, 0x00);
        cmd(ESC, 0x45, 0x00);
        text("------------------------------------------------\n");

        cmd(ESC, 0x45, 0x01);
        text("CLIENTE:  " + o.optString("customer_name") + "\n");
        text("TELEFONO: " + o.optString("customer_phone") + "\n");
        String addr = o.optString("customer_address", "");
        if (!addr.isEmpty()) text("DIRECCION: " + addr + "\n");
        cmd(ESC, 0x45, 0x00);
        text("================================================\n");

        cmd(ESC, 0x45, 0x01);
        text(lr("CANT  DESCRIPCION", "TOTAL") + "\n");
        cmd(ESC, 0x45, 0x00);
        text("------------------------------------------------\n");

        JSONArray items = o.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                int qty = it.optInt("qty", 1);
                String name = it.optString("name");
                double price = it.optDouble("unitPrice", 0);
                double lineTotal = price * qty;

                cmd(ESC, 0x45, 0x01);
                cmd(GS, 0x21, 0x01);
                text(lr(qty + "x " + name, money(lineTotal)) + "\n");
                cmd(ESC, 0x45, 0x00);

                JSONArray opts = it.optJSONArray("options");
                if (opts != null) {
                    for (int j = 0; j < opts.length(); j++) {
                        text("   * " + opts.optString(j) + "\n");
                    }
                }
                cmd(GS, 0x21, 0x00);
                text("------------------------------------------------\n");
            }
        }

        text(lr("Subtotal:", money(o.optDouble("subtotal", 0))) + "\n");
        text(lr("Costo de Entrega:", money(o.optDouble("delivery_fee", 0))) + "\n");

        cmd(ESC, 0x45, 0x01);
        cmd(GS, 0x21, 0x01);
        text(lr("TOTAL A PAGAR:", money(o.optDouble("total", 0)) + " CLP") + "\n");
        cmd(GS, 0x21, 0x00);
        cmd(ESC, 0x45, 0x00);

        text("------------------------------------------------\n");
        cmd(ESC, 0x45, 0x01);
        text("PAGO: " + o.optString("payment_detail") + "\n");
        String notes = o.optString("notes", "");
        if (!notes.isEmpty() && !"Sin comentarios".equals(notes)) {
            text("NOTAS: " + notes + "\n");
        }
        cmd(ESC, 0x45, 0x00);
        text("================================================\n");

        cmd(ESC, 0x61, 0x01);
        text("Gracias por su preferencia!\n\n\n\n\n\n");
        cmd(GS, 0x56, 0x42, 0x00);

        return out.toByteArray();
    }
}
