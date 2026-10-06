package com.variado.sportcal;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String CALENDAR_URL =
            "https://raw.githubusercontent.com/DavidPerez3/SportCal/main/data/sports-calendar.json";
    private static final String PREFS = "sportcal";
    private static final String CACHE_KEY = "calendar_json";

    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0A0B0F);
        getWindow().setNavigationBarColor(0xFF0A0B0F);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        WebView.enableSlowWholeDocumentDraw();
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0A0B0F);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectBundledCalendar();

                String cached = prefs.getString(CACHE_KEY, null);
                if (cached != null) {
                    try {
                        validateCalendar(cached);
                        injectCalendar(cached, "cache");
                    } catch (Exception ignored) {
                        prefs.edit().remove(CACHE_KEY).apply();
                    }
                }

                syncCalendarFromNetwork();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    public final class AndroidBridge {
        @JavascriptInterface
        public void exportPng(String filename) {
            runOnUiThread(() -> exportAndSharePng(filename));
        }

        @JavascriptInterface
        public void printPdf(String title) {
            runOnUiThread(() -> printCurrentView(title));
        }

        @JavascriptInterface
        public void syncCalendar() {
            syncCalendarFromNetwork();
        }
    }

    private void injectBundledCalendar() {
        try (InputStream input = getAssets().open("sports-calendar.json")) {
            String json = readAll(input);
            validateCalendar(json);
            injectCalendar(json, "incluido");
        } catch (Exception e) {
            reportSyncError("No se pudo cargar el calendario incluido");
        }
    }

    private void syncCalendarFromNetwork() {
        if (!syncing.compareAndSet(false, true)) {
            return;
        }

        runOnUiThread(() ->
                webView.evaluateJavascript(
                        "window.setCalendarSyncing && window.setCalendarSyncing(true)", null));

        executor.execute(() -> {
            try {
                String json = fetchText(CALENDAR_URL);
                validateCalendar(json);
                prefs.edit().putString(CACHE_KEY, json).apply();
                injectCalendar(json, "online");
            } catch (Exception e) {
                reportSyncError("Sin conexión o actualización no disponible");
            } finally {
                syncing.set(false);
                runOnUiThread(() ->
                        webView.evaluateJavascript(
                                "window.setCalendarSyncing && window.setCalendarSyncing(false)", null));
            }
        });
    }

    private String fetchText(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "SportCal-Android/0.3");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (InputStream input = connection.getInputStream()) {
                return readAll(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readAll(InputStream input) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
        }
        return out.toString();
    }

    private void validateCalendar(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray events = root.optJSONArray("events");
        if (events == null || events.length() < 1000) {
            throw new IllegalStateException("Calendario incompleto");
        }

        JSONObject counts = root.optJSONObject("counts");
        if (counts == null
                || counts.optInt("laliga", 0) < 300
                || counts.optInt("champions", 0) < 100
                || counts.optInt("europa", 0) < 100
                || counts.optInt("euroleague", 0) < 300) {
            throw new IllegalStateException("Una competición está incompleta");
        }
    }

    private void injectCalendar(String json, String source) {
        final String script =
                "window.applyCalendarData && window.applyCalendarData(JSON.parse("
                        + JSONObject.quote(json)
                        + "),"
                        + JSONObject.quote(source)
                        + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private void reportSyncError(String message) {
        final String script =
                "window.setCalendarSyncError && window.setCalendarSyncError("
                        + JSONObject.quote(message)
                        + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private void exportAndSharePng(String filename) {
        try {
            Picture picture = webView.capturePicture();
            int width = picture.getWidth();
            int height = Math.min(picture.getHeight(), 12000);
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("Vista no preparada");
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            picture.draw(canvas);

            String safeName = (filename == null || filename.trim().isEmpty())
                    ? "SportCal" : filename.trim();
            if (!safeName.endsWith(".png")) {
                safeName += ".png";
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SportCal");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("No se pudo crear el archivo");
            }

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IllegalStateException("No se pudo escribir la imagen");
                }
            }
            bitmap.recycle();

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir calendario"));
            webView.evaluateJavascript("document.body.classList.remove('exporting')", null);
        } catch (Exception e) {
            webView.evaluateJavascript("document.body.classList.remove('exporting')", null);
            Toast.makeText(this,
                    "No se pudo exportar PNG: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void printCurrentView(String title) {
        try {
            PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
            String jobName = (title == null || title.trim().isEmpty())
                    ? "SportCal" : title;
            printManager.print(
                    jobName,
                    webView.createPrintDocumentAdapter(jobName),
                    new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                            .build());
        } catch (Exception e) {
            Toast.makeText(this,
                    "No se pudo abrir la exportación PDF",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
