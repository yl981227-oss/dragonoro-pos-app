package com.dragonoro.pos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service that polls Supabase for new orders and prints them
 * directly over TCP. This is what makes printing work while the APP is in
 * the background - the WebView's JS timers get throttled by Android, but a
 * foreground service keeps running.
 */
public class OrderPollingService extends Service {

    private static final String TAG = "OrderPoller";
    private static final String CHANNEL_ID = "pos_orders";
    private static final long POLL_MS = 5000L;
    private static final long RETRY_MS = 20000L;

    private volatile boolean running = true;
    private Thread worker;

    private final Map<Long, Long> lastAttempt = new HashMap<>();
    private final Set<Long> issueNotified = new HashSet<>();
    private PrintJournal journal;
    private PrintJournalStore journalStore;
    private static final String ALERT_CHANNEL = "pos_print_issues";
    private static final int PAGE_SIZE = 100;
    private long pageCursor = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        journalStore = new PrintJournalStore(this);
        journal = new PrintJournal(journalStore);
        // Interrupted writes are uncertain, not automatically retried on startup.
        for (Map.Entry<String, ?> entry : journalStore.preferences().getAll().entrySet()) {
            if (entry.getKey().startsWith("state_") && "SENDING".equals(entry.getValue())) {
                try { journal.uncertain(Long.parseLong(entry.getKey().substring(6))); }
                catch (NumberFormatException ignored) { }
            }
        }
        createChannel();
        startForeground(1, buildNotification("Escuchando pedidos en segundo plano"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::loop, "order-poller");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int tickCount = 0;
    private int printerFailCount = 0;
    private boolean printerOffline = false;

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (Exception e) {
                Log.w(TAG, "poll error: " + e.getMessage());
            }

            // 每 6 轮（约 30 秒）探一次打印机，连续 3 次失败就告警
            tickCount++;
            if (tickCount % 6 == 0) {
                try {
                    checkPrinterHealth();
                } catch (Exception e) {
                    Log.w(TAG, "printer check error: " + e.getMessage());
                }
            }

            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void checkPrinterHealth() {
        SharedPreferences sp = getSharedPreferences(SupabaseConfig.PREFS, MODE_PRIVATE);
        String ip = sp.getString(SupabaseConfig.KEY_IP, "");
        int port = sp.getInt(SupabaseConfig.KEY_PORT, SupabaseConfig.DEFAULT_PORT);
        if (ip == null || ip.isEmpty()) return;

        if (pingPrinter(ip, port)) {
            if (printerOffline) {
                printerOffline = false;
                printerFailCount = 0;
                notifySimple("Impresora reconectada", "Ya puedes imprimir de nuevo");
                Log.i(TAG, "printer back online");
            } else {
                printerFailCount = 0;
            }
        } else {
            printerFailCount++;
            if (printerFailCount >= 3 && !printerOffline) {
                printerOffline = true;
                notifySimple("Impresora desconectada", "Revisa la conexión y la alimentación. El estado del papel no se detecta por TCP.");
                Log.w(TAG, "printer offline after " + printerFailCount + " failures");
            }
        }
    }

    private boolean pingPrinter(String ip, int port) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ip, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void notifySimple(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, ALERT_CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        android.app.PendingIntent openApp = android.app.PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        nm.notify((int) (System.currentTimeMillis() % 100000),
                b.setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setContentIntent(openApp)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setAutoCancel(true)
                        .build());
    }

    private void pollOnce() throws Exception {
        // Keyset pagination: a waiting reservation cannot block later immediate orders.
        String body = httpGet(SupabaseConfig.URL + "/rest/v1/orders?status=eq.pending&select=*"
                + "&order=id.asc&limit=" + PAGE_SIZE + "&id=gt." + pageCursor);
        JSONArray arr = new JSONArray(body);
        long nextCursor = pageCursor;
        for (int i = 0; i < arr.length() && running; i++) {
            JSONObject order = arr.getJSONObject(i);
            long id = order.optLong("id", 0);
            if (id <= nextCursor) throw new IllegalStateException("Invalid order pagination");
            nextCursor = id;
            processPendingOrder(order);
        }
        pageCursor = arr.length() < PAGE_SIZE ? 0 : nextCursor;
        // Entries already SENT still need status synchronization even when no longer pending.
        syncSentJournalEntries();
        if (lastAttempt.size() > 5000) lastAttempt.clear();
    }

    private void processPendingOrder(JSONObject order) {
        long id = order.optLong("id", 0);
        journalStore.remember(id, order.optString("order_code", "Pedido " + id));
        PrintJournal.State state = journal.state(id);
        if (state == PrintJournal.State.SYNCED || state == PrintJournal.State.SENT) return;
        if (state == PrintJournal.State.SENDING || state == PrintJournal.State.UNCERTAIN || state == PrintJournal.State.REVIEW) {
            notifyIssueOnce(id, "Revisa el pedido y el papel en 'Revisar impresión'. No se reenviará automáticamente.");
            return;
        }
        OrderSchedule.Decision decision = OrderSchedule.evaluate(order.optString("timing_info", ""),
                order.optString("created_at", ""), System.currentTimeMillis());
        if (state != PrintJournal.State.APPROVED) {
            if (decision.action == OrderSchedule.Action.WAIT) return;
            if (decision.action == OrderSchedule.Action.REVIEW) {
                journal.review(id);
                notifyIssueOnce(id, decision.reason);
                return;
            }
        }
        long now = System.currentTimeMillis();
        Long previous = lastAttempt.get(id);
        if (previous != null && now - previous < RETRY_MS) return;
        lastAttempt.put(id, now);
        if (printOrder(order)) {
            // Preserve the attempt timestamp on PATCH failure. The end-of-page sync
            // pass must not immediately bypass the same 20-second retry backoff.
            if (markPreparing(id)) {
                journal.synced(id);
                lastAttempt.remove(id);
            }
            notifyPrinted(order.optString("order_code"));
        }
    }

    private void notifyIssueOnce(long id, String reason) {
        if (issueNotified.add(id)) notifySimple("Pedido requiere revisión: " + journalStore.code(id), reason);
    }

    private void syncSentJournalEntries() {
        for (Map.Entry<String, ?> entry : journalStore.preferences().getAll().entrySet()) {
            if (!running) return;
            if (!entry.getKey().startsWith("state_")) continue;
            long id;
            try { id = Long.parseLong(entry.getKey().substring(6)); }
            catch (NumberFormatException ignored) { continue; }
            PrintJournal.State state = journal.state(id);
            if (state == PrintJournal.State.UNCERTAIN || state == PrintJournal.State.REVIEW) {
                notifyIssueOnce(id, "Revisa el papel y confirma la acción en el APP.");
            }
            if (state == PrintJournal.State.SENT) {
                Long last = lastAttempt.get(id);
                long now = System.currentTimeMillis();
                if (last != null && now - last < RETRY_MS) continue;
                lastAttempt.put(id, now);
                if (markPreparing(id)) { journal.synced(id); lastAttempt.remove(id); }
            }
        }
    }

    private boolean printOrder(JSONObject order) {
        SharedPreferences sp = getSharedPreferences(SupabaseConfig.PREFS, MODE_PRIVATE);
        String ip = sp.getString(SupabaseConfig.KEY_IP, "");
        int port = sp.getInt(SupabaseConfig.KEY_PORT, SupabaseConfig.DEFAULT_PORT);
        if (ip == null || ip.isEmpty()) return false;
        long id = order.optLong("id", 0);
        SocketChannel channel = null;
        boolean sending = false;
        try {
            byte[] data = new EscPosBuilder().build(order);
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(ip, port));
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (!channel.finishConnect()) {
                if (System.nanoTime() >= deadline) throw new java.io.IOException("Printer connect timeout");
                Thread.sleep(20L);
            }
            // Fail closed if the local journal cannot be persisted. No bytes have been sent yet.
            if (!journal.begin(id)) {
                notifyIssueOnce(id, "No se pudo guardar el registro local. Revisa el almacenamiento.");
                return false;
            }
            sending = true;
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                if (written < 0) throw new java.io.IOException("Printer closed connection");
                if (System.nanoTime() >= deadline) throw new java.io.IOException("Printer write timeout");
                if (written == 0) Thread.sleep(20L);
            }
            // SENT means bytes dispatched; NOT proof of physical paper output.
            if (!journal.sent(id)) {
                journal.uncertain(id);
                notifyIssueOnce(id, "Datos enviados pero registro no confirmado. Comprueba el papel antes de repetir.");
                return false;
            }
            printerFailCount = 0;
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Printer operation failed: " + error.getClass().getSimpleName());
            if (sending) {
                journal.uncertain(id);
                notifyIssueOnce(id, "Envío interrumpido. Puede existir un ticket parcial; revisa antes de repetir.");
            }
            printerFailCount++;
            if (printerFailCount >= 3 && !printerOffline) {
                printerOffline = true;
                notifySimple("Impresora desconectada", "Revisa la conexión y la alimentación. El estado del papel no se detecta por TCP.");
            }
            return false;
        } finally {
            if (channel != null) try { channel.close(); } catch (Exception ignored) { }
        }
    }

    private boolean markPreparing(long id) {
        return markPreparing(id, true);
    }

    private boolean markPreparing(long id, boolean allowRefresh) {
        HttpURLConnection c = null;
        try {
            // Never regress a ready/delivered order during a retry.
            URL url = new URL(SupabaseConfig.URL + "/rest/v1/orders?id=eq." + id + "&status=eq.pending");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("PATCH");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("apikey", SupabaseConfig.KEY);
            c.setRequestProperty("Authorization", "Bearer " + authToken());
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Prefer", "return=representation");
            byte[] b = "{\"status\":\"preparing\"}".getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(b); }
            int status = c.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED && allowRefresh && refreshAuthToken()) {
                return markPreparing(id, false);
            }
            if (status < 200 || status >= 300) throw new java.io.IOException("Status update HTTP " + status);
            if (status == HttpURLConnection.HTTP_NO_CONTENT) {
                throw new java.io.IOException("Status update returned no row confirmation");
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
            JSONArray updated = new JSONArray(response.toString());
            if (updated.length() == 0) throw new java.io.IOException("Order was not pending or update matched zero rows");
            return true;
        } catch (Exception e) {
            notifyIssueOnce(id, "Ticket enviado, pero el estado no pudo sincronizarse. Se reintentará sin reimprimir.");
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String authToken() {
        SharedPreferences sp = getSharedPreferences(SupabaseConfig.PREFS, MODE_PRIVATE);
        String token = sp.getString(SupabaseConfig.KEY_ACCESS_TOKEN, "");
        return token == null || token.isEmpty() ? SupabaseConfig.KEY : token;
    }

    private boolean refreshAuthToken() {
        SharedPreferences sp = getSharedPreferences(SupabaseConfig.PREFS, MODE_PRIVATE);
        String refresh = sp.getString(SupabaseConfig.KEY_REFRESH_TOKEN, "");
        if (refresh == null || refresh.isEmpty()) return false;
        HttpURLConnection c = null;
        try {
            URL url = new URL(SupabaseConfig.URL + "/auth/v1/token?grant_type=refresh_token");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("apikey", SupabaseConfig.KEY); c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(("{\"refresh_token\":\"" + refresh.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}").getBytes(StandardCharsets.UTF_8));
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return false;
            JSONObject body = new JSONObject(readAll(c));
            String access = body.optString("access_token", "");
            String nextRefresh = body.optString("refresh_token", refresh);
            if (access.isEmpty()) return false;
            sp.edit().putString(SupabaseConfig.KEY_ACCESS_TOKEN, access).putString(SupabaseConfig.KEY_REFRESH_TOKEN, nextRefresh).commit();
            return true;
        } catch (Exception error) { return false; }
        finally { if (c != null) c.disconnect(); }
    }

    private String readAll(HttpURLConnection c) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private String httpGet(String urlStr) throws Exception {
        return httpGet(urlStr, true);
    }

    private String httpGet(String urlStr, boolean allowRefresh) throws Exception {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("apikey", SupabaseConfig.KEY);
            c.setRequestProperty("Authorization", "Bearer " + authToken());
            int status = c.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED && allowRefresh && refreshAuthToken()) {
                return httpGet(urlStr, false);
            }
            if (status < 200 || status >= 300) throw new java.io.IOException("Order query HTTP " + status);
            return readAll(c);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Pedidos", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Escucha de pedidos en segundo plano");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
                nm.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL,
                        "Alertas de impresión", NotificationManager.IMPORTANCE_HIGH));
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("China Ideal POS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    private void notifyPrinted(String code) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        nm.notify((int) (System.currentTimeMillis() % 100000),
                b.setContentTitle("Ticket enviado a la impresora")
                        .setContentText(code)
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setAutoCancel(true)
                        .build());
    }
}
