package com.variado.sportcal;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
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

import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0A0B0F);
        getWindow().setNavigationBarColor(0xFF0A0B0F);

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
        webView.setWebViewClient(new WebViewClient());
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
    }

    private void exportAndSharePng(String filename) {
        try {
            Picture picture = webView.capturePicture();
            int width = picture.getWidth();
            int height = Math.min(picture.getHeight(), 12000);
            if (width <= 0 || height <= 0) throw new IllegalStateException("Vista no preparada");

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            picture.draw(canvas);

            String safeName = (filename == null || filename.trim().isEmpty()) ? "SportCal" : filename.trim();
            if (!safeName.endsWith(".png")) safeName += ".png";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SportCal");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("No se pudo crear el archivo");

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
            Toast.makeText(this, "No se pudo exportar PNG: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void printCurrentView(String title) {
        try {
            PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
            String jobName = (title == null || title.trim().isEmpty()) ? "SportCal" : title;
            printManager.print(jobName, webView.createPrintDocumentAdapter(jobName), new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build());
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la exportación PDF", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
