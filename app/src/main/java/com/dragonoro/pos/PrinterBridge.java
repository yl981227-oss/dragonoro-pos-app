package com.dragonoro.pos;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Exposed to JavaScript as window.AndroidPrinter.
 * Sends raw ESC/POS bytes straight to the printer's RAW port, no browser,
 * no RawBT, no confirmation dialog.
 */
public class PrinterBridge {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PrinterBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Stores the printer address so the background polling service can print
     * even when the WebView is not running.
     */
    @JavascriptInterface
    public void saveConfig(String ip, int port) {
        SharedPreferences sp = context.getSharedPreferences(SupabaseConfig.PREFS, Context.MODE_PRIVATE);
        sp.edit()
                .putString(SupabaseConfig.KEY_IP, ip == null ? "" : ip.trim())
                .putInt(SupabaseConfig.KEY_PORT, port > 0 ? port : SupabaseConfig.DEFAULT_PORT)
                .apply();
    }

    @JavascriptInterface
    public void print(String ip, int port, String base64Data) {
        if (ip == null || base64Data == null || base64Data.isEmpty()) {
            toast("Datos de impresion vacios");
            return;
        }
        final String host = ip.trim();
        final int p = port > 0 ? port : 9100;

        final byte[] data;
        try {
            data = Base64.decode(base64Data, Base64.DEFAULT);
        } catch (Exception e) {
            toast("Base64 invalido");
            return;
        }

        new Thread(() -> {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, p), 4000);
                socket.setSoTimeout(4000);
                OutputStream os = socket.getOutputStream();
                os.write(data);
                os.flush();
                toast("Ticket enviado a " + host);
            } catch (Exception e) {
                toast("Error impresora: " + e.getClass().getSimpleName());
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }).start();
    }

    @JavascriptInterface
    public void ping(String ip, int port) {
        if (ip == null || ip.trim().isEmpty()) {
            toast("IP vacia");
            return;
        }
        final String host = ip.trim();
        final int p = port > 0 ? port : 9100;

        new Thread(() -> {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, p), 3000);
                toast("Conexion OK: " + host + ":" + p);
            } catch (Exception e) {
                toast("Sin conexion a " + host + ":" + p);
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }).start();
    }

    private void toast(final String msg) {
        mainHandler.post(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
    }
}
