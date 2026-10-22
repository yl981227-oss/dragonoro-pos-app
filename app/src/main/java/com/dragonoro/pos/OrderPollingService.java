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
import java.nio.charset.StandardCharsets;
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

    private final Set<Long> handled = new HashSet<>();
    private final Map<Long, Long> lastAttempt = new HashMap<>();
    private boolean initialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (Exception e) {
                Log.w(TAG, "poll error: " + e.getMessage());
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void pollOnce() throws Exception {
        String body = httpGet(SupabaseConfig.URL
                + "/rest/v1/orders?status=eq.pending&select=*&order=id.asc&limit=20");
        JSONArray arr = new JSONArray(body);

        if (!initialized) {
            // First run: treat existing pending orders as already handled so the
            // app does not dump a pile of old tickets on startup.
            for (int i = 0; i < arr.length(); i++) {
                handled.add(arr.getJSONObject(i).optLong("id"));
            }
            initialized = true;
            Log.i(TAG, "initialized, skipped " + arr.length() + " existing pending orders");
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            long id = o.optLong("id");
            if (handled.contains(id)) continue;

            long now = System.currentTimeMillis();
            Long prev = lastAttempt.get(id);
            if (prev != null && now - prev < RETRY_MS) continue;
            lastAttempt.put(id, now);

            if (printOrder(o)) {
                handled.add(id);
                lastAttempt.remove(id);
                markPreparing(id);
                notifyPrinted(o.optString("order_code"));
            }
        }
    }

    private boolean printOrder(JSONObject o) {
        SharedPreferences sp = getSharedPreferences(SupabaseConfig.PREFS, MODE_PRIVATE);
        String ip = sp.getString(SupabaseConfig.KEY_IP, "");
        int port = sp.getInt(SupabaseConfig.KEY_PORT, SupabaseConfig.DEFAULT_PORT);
        if (ip == null || ip.isEmpty()) {
            Log.w(TAG, "no printer ip configured");
            return false;
        }
        byte[] data = new EscPosBuilder().build(o);
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ip, port), 4000);
            s.setSoTimeout(4000);
            OutputStream os = s.getOutputStream();
            os.write(data);
            os.flush();
            Log.i(TAG, "printed " + o.optString("order_code"));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "print failed: " + e.getMessage());
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

    private void markPreparing(long id) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(SupabaseConfig.URL + "/rest/v1/orders?id=eq." + id);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("PATCH");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("apikey", SupabaseConfig.KEY);
            c.setRequestProperty("Authorization", "Bearer " + SupabaseConfig.KEY);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Prefer", "return=minimal");
            byte[] b = "{\"status\":\"preparing\"}".getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(b);
            c.getResponseCode();
        } catch (Exception e) {
            Log.w(TAG, "mark failed: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("apikey", SupabaseConfig.KEY);
            c.setRequestProperty("Authorization", "Bearer " + SupabaseConfig.KEY);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
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
            if (nm != null) nm.createNotificationChannel(ch);
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
                b.setContentTitle("Ticket impreso")
                        .setContentText(code)
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setAutoCancel(true)
                        .build());
    }
}
