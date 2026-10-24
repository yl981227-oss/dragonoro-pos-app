package com.dragonoro.pos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Hosts the POS web app inside a WebView and exposes a native TCP printer bridge.
 * Loading the page from its HTTPS origin keeps Supabase CORS happy and lets
 * web updates reach the app without rebuilding the APK.
 */
public class MainActivity extends AppCompatActivity {

    private static final String START_URL =
            "https://yl981227-oss.github.io/my-menu/admin.html";
    private static final String ALLOWED_HOST = "yl981227-oss.github.io";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Button reviewPrint = new Button(this);
        reviewPrint.setText("Revisar impresión / pedidos pendientes");
        reviewPrint.setOnClickListener(view -> showPrintIssues());
        root.addView(reviewPrint, new LinearLayout.LayoutParams(-1, -2));
        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new PrinterBridge(this), "AndroidPrinter");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Uri destination = request.getUrl();
                if ("https".equals(destination.getScheme()) && ALLOWED_HOST.equals(destination.getHost())
                        && destination.getPath() != null && destination.getPath().startsWith("/my-menu/")) {
                    return false;
                }
                // External links (WhatsApp, maps, etc.) go to the system handler.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        // Append a timestamp so the WebView never serves a stale cached page.
        // This lets every web update reach all installed APPs on next launch.
        webView.loadUrl(START_URL + "?t=" + System.currentTimeMillis());

        requestNotifPermission();
        startPolling();
    }

    private void showPrintIssues() {
        PrintJournalStore store = new PrintJournalStore(this);
        PrintJournal journal = new PrintJournal(store);
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<String, ?> entry : store.preferences().getAll().entrySet()) {
            if (!entry.getKey().startsWith("state_")) continue;
            try {
                long id = Long.parseLong(entry.getKey().substring(6));
                PrintJournal.State state = journal.state(id);
                if (state == PrintJournal.State.UNCERTAIN || state == PrintJournal.State.REVIEW) ids.add(id);
            } catch (NumberFormatException ignored) { }
        }
        Collections.sort(ids);
        if (ids.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Revisar impresión")
                    .setMessage("No hay envíos dudosos pendientes. La conexión TCP no comprueba falta de papel ni atascos.")
                    .setPositiveButton("Cerrar", null).show();
            return;
        }
        String[] labels = new String[ids.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = store.code(ids.get(i)) + " · " + journal.state(ids.get(i)).name();
        new AlertDialog.Builder(this).setTitle("Pedidos que requieren revisión")
                .setItems(labels, (dialog, which) -> confirmPrintResolution(ids.get(which), store, journal))
                .setNegativeButton("Cerrar", null).show();
    }

    private void confirmPrintResolution(long id, PrintJournalStore store, PrintJournal journal) {
        new AlertDialog.Builder(this).setTitle(store.code(id))
                .setMessage("Comprueba el ticket físico y confirma la fecha con el cliente. " +
                        "Si ya existe el ticket, no lo repitas. Si autorizas el reenvío, se enviará ahora " +
                        "si el pedido sigue pendiente; podría duplicar un ticket enviado antes del corte.")
                .setPositiveButton("Ya tengo el ticket", (dialog, which) -> resolvePrint(journal, id, true))
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Autorizar envío ahora", (dialog, which) -> {
                    new AlertDialog.Builder(this).setTitle("Confirmar reenvío")
                            .setMessage("¿Verificaste el papel y necesitas enviar este pedido ahora? No se puede garantizar que un envío anterior no haya impreso.")
                            .setPositiveButton("Sí, autorizar", (confirm, button) -> resolvePrint(journal, id, false))
                            .setNegativeButton("Cancelar", null).show();
                }).show();
    }

    private void resolvePrint(PrintJournal journal, long id, boolean paperExists) {
        boolean saved = journal.resolve(id, paperExists);
        Toast.makeText(this, saved ? "Confirmación guardada. El servicio revisará el pedido." :
                "No se pudo guardar o el estado cambió. Actualiza y revisa de nuevo.", Toast.LENGTH_LONG).show();
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void startPolling() {
        Intent svc = new Intent(this, OrderPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
