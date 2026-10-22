package com.flasharcade.launcher.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import com.flasharcade.launcher.R;
import com.flasharcade.launcher.data.GameDao;
import com.flasharcade.launcher.data.GameDatabase;
import com.flasharcade.launcher.data.GameEntity;
import com.flasharcade.launcher.storage.GameImporter;
import com.flasharcade.launcher.util.SettingsStore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerActivity extends Activity {
    public static final String EXTRA_GAME_ID = "game_id";
    public static final String EXTRA_ALLOW_NETWORKING = "allow_networking";
    public static final String EXTRA_ALLOW_SCRIPT_ACCESS = "allow_script_access";
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final int MAX_DIAGNOSTIC_ENTRIES = 80;
    private static final int MAX_DIAGNOSTIC_ENTRY_LENGTH = 600;
    private static final Pattern URL_PATTERN =
            Pattern.compile("(?i)https?://[^\\s\\\"'<>]+");
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)(authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|token|password|secret|client[_-]?secret|api[_-]?key)(\\s*[:=]\\s*)([^\\s&;,]+)");
    private static final Pattern COOKIE_HEADER_PATTERN =
            Pattern.compile("(?i)(cookie|set-cookie)(\\s*[:=]\\s*)[^\\r\\n]*");
    private static final Pattern DEVICE_PATH_PATTERN = Pattern.compile(
            "(?i)(/data/(?:user(?:_de)?|data|app)/[^\\s\\\"'<>]+|/storage/emulated/\\d+/[^\\s\\\"'<>]+|/sdcard/[^\\s\\\"'<>]+)");
    private static final Pattern AUTH_SCHEME_PATTERN =
            Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]+=*");
    private static final String[] CONTROL_LABELS = {"↑", "↓", "←", "→"};
    private static final int[] CONTROL_KEYS = {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
    };
    private static final int[] CONTROL_VIEW_IDS = {
            R.id.control_up,
            R.id.control_down,
            R.id.control_left,
            R.id.control_right
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object diagnosticLock = new Object();
    private final List<String> diagnosticEntries = new ArrayList<>();
    private FrameLayout root;
    private TouchPassthroughFrameLayout controlsLayer;
    private WebView webView;
    private TextView status;
    private TextView diagnosticsButton;
    private TextView diagnosticsText;
    private LinearLayout diagnosticsPanel;
    private ScrollView diagnosticsScroll;
    private TextView[] controlViews;
    private TextView actionButton;
    private SettingsStore settings;
    private boolean rendererGone;
    private boolean userTouchedGame;
    private boolean startupTapAttempted;
    private boolean allowExternalNetworking;
    private boolean allowScriptAccess;
    private long diagnosticsStartedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        diagnosticsStartedAt = SystemClock.elapsedRealtime();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setImmersiveMode();
        settings = new SettingsStore(this);
        setContentView(R.layout.activity_game);
        root = findViewById(R.id.game_root);
        webView = findViewById(R.id.game_web_view);
        controlsLayer = findViewById(R.id.controls_layer);
        status = findViewById(R.id.player_status);
        setupDiagnostics();
        status.setText("Opening game…");
        status.setVisibility(View.VISIBLE);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setClickable(false);
        controlsLayer.setForwardingTarget(webView);
        long gameId = getIntent().getLongExtra(EXTRA_GAME_ID, -1);
        if (gameId < 0) {
            showFatalMessage("No game was selected.");
            return;
        }
        loadGame(gameId);
    }

    private void loadGame(long id) {
        GameDao dao = GameDatabase.get(this).gameDao();
        io.execute(() -> {
            GameEntity game;
            try {
                game = dao.getById(id);
                if (game == null) throw new IllegalStateException("This game is no longer in your library.");
                game.launchCount++;
                game.lastPlayedAt = System.currentTimeMillis();
                dao.update(game);
            } catch (RuntimeException error) {
                runOnUiThread(() -> showFatalMessage(error.getMessage() == null
                        ? "The game could not be opened." : error.getMessage()));
                return;
            }
            runOnUiThread(() -> startPlayer(game));
        });
    }

    private void startPlayer(GameEntity game) {
        if (isFinishing() || rendererGone) return;
        File swf = GameImporter.gameFile(this, game.localFileName);
        if (!swf.isFile()) {
            showFatalMessage("The imported SWF is missing. Remove it from the library and import it again.");
            return;
        }
        allowExternalNetworking = getIntent().getBooleanExtra(EXTRA_ALLOW_NETWORKING, false);
        allowScriptAccess = getIntent().getBooleanExtra(EXTRA_ALLOW_SCRIPT_ACCESS, false);
        if (status.getParent() != null) root.removeView(status);

        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        File gamesDir = new File(getFilesDir(), "games");
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new LocalAssetPathHandler(getAssets()))
                .addPathHandler("/games/", new WebViewAssetLoader.InternalStoragePathHandler(this, gamesDir))
                .build();

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webSettings.setSupportMultipleWindows(false);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webSettings.setSafeBrowsingEnabled(true);
        }
        webView.addJavascriptInterface(new PlayerDiagnosticsBridge(), "FlashDiagnostics");

        webView.setOnTouchListener((view, event) -> {
            if (!startupTapAttempted && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                userTouchedGame = true;
            }
            return false;
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                boolean isError = message.messageLevel() == ConsoleMessage.MessageLevel.ERROR;
                boolean isWarning = message.messageLevel() == ConsoleMessage.MessageLevel.WARNING;
                int priority = isError ? Log.ERROR : isWarning ? Log.WARN : Log.DEBUG;
                String detail = message.message() + " (" + message.sourceId()
                        + ":" + message.lineNumber() + ")";
                Log.println(priority, "FlashArcade/Ruffle", sanitizeDiagnosticText(detail));
                if (isError || isWarning) {
                    recordDiagnostic("Ruffle console", detail, false);
                }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                WebResourceResponse response = assetLoader.shouldInterceptRequest(uri);
                if (response == null && isLocalPlayerResource(uri)) {
                    recordDiagnostic("Resource load",
                            "Could not load " + safeResourceLabel(uri) + " (local resource unavailable).",
                            false);
                }
                return response;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                boolean isHttps = "https".equalsIgnoreCase(uri.getScheme());
                boolean isLocalPlayer = isHttps && ASSET_HOST.equalsIgnoreCase(uri.getHost());
                return !isLocalPlayer && !(allowExternalNetworking && isHttps);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (status != null && status.getParent() != null) root.removeView(status);
                view.requestFocus();
                view.postDelayed(() -> dispatchInitialCanvasTap(view), 1_500L);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (isRelevantRequest(request)) {
                    CharSequence errorDescription = error == null ? null : error.getDescription();
                    String description = errorDescription == null ? "Unknown WebView resource error"
                            : errorDescription.toString();
                    String detail = safeResourceLabel(request.getUrl()) + " — " + description;
                    recordDiagnostic("WebView load", detail, request.isForMainFrame());
                }
                if (request.isForMainFrame()) {
                    showRecoverableMessage("Ruffle could not load this SWF. Check the imported file and try again.");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (isRelevantRequest(request)) {
                    String detail = safeResourceLabel(request.getUrl()) + " returned HTTP "
                            + response.getStatusCode() + ".";
                    recordDiagnostic("HTTP resource error", detail, request.isForMainFrame());
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                rendererGone = true;
                controlsLayer.setForwardingTarget(null);
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
                webView = null;
                showFatalMessage("The game renderer stopped unexpectedly. The launcher is still open; go back and try another game.");
                recordDiagnostic("WebView renderer",
                        detail.didCrash() ? "The game renderer crashed."
                                : "The game renderer was terminated.",
                        true);
                return true;
            }
        });
        controlsLayer.setForwardingTarget(webView);
        addControls();
        if (allowExternalNetworking || allowScriptAccess) {
            Log.w("FlashArcade/Ruffle",
                    "Relaxed Ruffle access is enabled for this game launch: networking="
                            + allowExternalNetworking + ", scriptAccess=" + allowScriptAccess);
        }
        webView.loadUrl(createRunnerUrl(
                game.localFileName, game.title, allowExternalNetworking, allowScriptAccess));
        webView.requestFocus();
    }

    private void setupDiagnostics() {
        diagnosticsButton = findViewById(R.id.diagnostics_button);
        diagnosticsPanel = findViewById(R.id.player_diagnostics_panel);
        diagnosticsText = findViewById(R.id.diagnostics_text);
        diagnosticsScroll = findViewById(R.id.diagnostics_scroll);
        diagnosticsButton.setVisibility(View.VISIBLE);
        diagnosticsButton.setOnClickListener(view -> showDiagnosticsPanel());
        findViewById(R.id.diagnostics_close).setOnClickListener(view -> hideDiagnosticsPanel());
        findViewById(R.id.diagnostics_copy).setOnClickListener(view -> copyDiagnostics());
        findViewById(R.id.diagnostics_share).setOnClickListener(view -> shareDiagnostics());
    }

    private void showDiagnosticsPanel() {
        if (root == null || diagnosticsPanel == null) return;
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            root.post(this::showDiagnosticsPanel);
            return;
        }

        int width = Math.max(dp(1), root.getWidth() - dp(24));
        int availableHeight = Math.max(dp(1), root.getHeight() - dp(24));
        int height = Math.min(availableHeight, Math.round(root.getHeight() * 0.78f));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, height, Gravity.CENTER);
        diagnosticsPanel.setLayoutParams(params);
        diagnosticsPanel.setVisibility(View.VISIBLE);
        diagnosticsPanel.bringToFront();
        diagnosticsText.setText(buildDiagnosticReport());
        diagnosticsScroll.post(() -> diagnosticsScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void hideDiagnosticsPanel() {
        if (diagnosticsPanel != null) diagnosticsPanel.setVisibility(View.GONE);
        if (webView != null && !rendererGone) webView.requestFocus();
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Could not access the clipboard.", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "Flash player diagnostics", buildDiagnosticReport()));
        Toast.makeText(this, "Diagnostics copied.", Toast.LENGTH_SHORT).show();
    }

    private void shareDiagnostics() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, buildDiagnosticReport());
        try {
            startActivity(Intent.createChooser(share, "Share player diagnostics"));
        } catch (RuntimeException error) {
            Toast.makeText(this, "No app is available to share the report.", Toast.LENGTH_SHORT).show();
        }
    }

    private void recordDiagnostic(String source, String message, boolean openPanel) {
        if (root == null || isFinishing() || isDestroyed()) return;
        String safeSource = sanitizeDiagnosticText(source);
        String safeMessage = sanitizeDiagnosticText(message);
        if (safeSource.isEmpty()) safeSource = "Player";
        if (safeSource.length() > 48) safeSource = safeSource.substring(0, 48);
        if (safeMessage.isEmpty()) safeMessage = "No details provided.";
        final String entry = String.format(Locale.US, "[+%.1fs] %s: %s",
                (SystemClock.elapsedRealtime() - diagnosticsStartedAt) / 1000f,
                safeSource, safeMessage);
        final boolean showPanel = openPanel || "load failure".equalsIgnoreCase(safeSource);

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || diagnosticsText == null) return;
            boolean firstEntry;
            synchronized (diagnosticLock) {
                firstEntry = diagnosticEntries.isEmpty();
                if (diagnosticEntries.size() >= MAX_DIAGNOSTIC_ENTRIES) {
                    diagnosticEntries.remove(0);
                }
                diagnosticEntries.add(entry);
            }
            diagnosticsText.setText(buildDiagnosticReport());
            diagnosticsButton.setVisibility(View.VISIBLE);
            diagnosticsScroll.post(() -> diagnosticsScroll.fullScroll(View.FOCUS_DOWN));
            if (showPanel) {
                showDiagnosticsPanel();
            } else if (firstEntry) {
                Toast.makeText(this, "Player issue captured. Tap ISSUES to view the report.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private String buildDiagnosticReport() {
        StringBuilder report = new StringBuilder("Flash Arcade player diagnostics\n");
        List<String> entries;
        synchronized (diagnosticLock) {
            entries = new ArrayList<>(diagnosticEntries);
        }
        report.append("Captured entries: ").append(entries.size()).append("\n\n");
        report.append("Ruffle networking: ")
                .append(allowExternalNetworking ? "all (opt-in)" : "internal")
                .append('\n');
        report.append("Flash ExternalInterface: ")
                .append(allowScriptAccess ? "enabled (opt-in)" : "disabled")
                .append("\n\n");
        if (entries.isEmpty()) {
            report.append("No player errors captured.");
        } else {
            for (String entry : entries) report.append(entry).append('\n');
        }
        return report.toString();
    }

    private boolean isRelevantRequest(WebResourceRequest request) {
        if (request.isForMainFrame()) return true;
        String scheme = request.getUrl().getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static boolean isLocalPlayerResource(Uri uri) {
        if (uri == null || !ASSET_HOST.equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getPath();
        return path != null && (path.startsWith("/assets/") || path.startsWith("/games/"));
    }

    private static String safeResourceLabel(Uri uri) {
        if (uri == null) return "[unknown resource]";
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getEncodedPath();
        if (ASSET_HOST.equalsIgnoreCase(host) && path != null
                && path.startsWith("/games/")) {
            path = "/games/[imported-swf].swf";
        } else if (path != null) {
            path = redactSensitivePath(path);
        }
        if (scheme == null || host == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return path == null || path.isEmpty() ? "[unknown resource]" : path;
        }
        return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                + (path == null || path.isEmpty() ? "/" : path);
    }

    private static String redactSensitivePath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder safePath = new StringBuilder();
        boolean redactNext = false;
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            safePath.append('/');
            if (redactNext) {
                safePath.append("[redacted]");
                redactNext = false;
                continue;
            }
            String lower = segment.toLowerCase(Locale.ROOT);
            if ("token".equals(lower) || "auth".equals(lower) || "authorization".equals(lower)
                    || "session".equals(lower) || "signature".equals(lower)
                    || "password".equals(lower) || "secret".equals(lower) || "key".equals(lower)) {
                safePath.append(segment);
                redactNext = true;
            } else if (segment.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                    || segment.length() > 24) {
                safePath.append("[id]");
            } else {
                safePath.append(segment);
            }
        }
        if (safePath.length() > 1 && path.endsWith("/")) safePath.append('/');
        return safePath.length() == 0 ? "/" : safePath.toString();
    }

    private static String sanitizeDiagnosticText(String value) {
        if (value == null) return "";
        Matcher urlMatcher = URL_PATTERN.matcher(value);
        StringBuffer urlSafe = new StringBuffer();
        while (urlMatcher.find()) {
            String candidate = urlMatcher.group();
            int end = candidate.length();
            while (end > 0 && ".,;:!?)]}".indexOf(candidate.charAt(end - 1)) >= 0) end--;
            String url = candidate.substring(0, end);
            String suffix = candidate.substring(end);
            String safeUrl = safeResourceLabel(Uri.parse(url));
            urlMatcher.appendReplacement(urlSafe,
                    Matcher.quoteReplacement(safeUrl + suffix));
        }
        urlMatcher.appendTail(urlSafe);

        String safe = AUTH_SCHEME_PATTERN.matcher(urlSafe.toString())
                .replaceAll("$1 [redacted]");
        safe = COOKIE_HEADER_PATTERN.matcher(safe).replaceAll("$1$2[redacted]");
        safe = CREDENTIAL_PATTERN.matcher(safe).replaceAll("$1$2[redacted]");
        safe = DEVICE_PATH_PATTERN.matcher(safe).replaceAll("[device path]");
        StringBuilder printable = new StringBuilder(Math.min(safe.length(), MAX_DIAGNOSTIC_ENTRY_LENGTH));
        boolean previousSpace = false;
        for (int i = 0; i < safe.length(); i++) {
            char character = safe.charAt(i);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                if (!previousSpace) printable.append(' ');
                previousSpace = true;
            } else {
                printable.append(character);
                previousSpace = false;
            }
            if (printable.length() >= MAX_DIAGNOSTIC_ENTRY_LENGTH) break;
        }
        return printable.toString().trim();
    }

    public final class PlayerDiagnosticsBridge {
        @JavascriptInterface
        public void report(String source, String message) {
            String safeSource = sanitizeDiagnosticText(source);
            boolean shouldOpenPanel = "load failure".equalsIgnoreCase(safeSource);
            recordDiagnostic(safeSource, message, shouldOpenPanel);
        }
    }

    private void dispatchInitialCanvasTap(WebView target) {
        if (isFinishing() || rendererGone || target != webView
                || userTouchedGame || startupTapAttempted
                || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return;
        }

        // One best-effort tap helps SWFs that wait at a post-load screen. It is
        // skipped if the player has already received a real tap from the user.
        startupTapAttempted = true;
        final float x = target.getWidth() / 2f;
        final float y = target.getHeight() / 2f;
        final long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        try {
            target.dispatchTouchEvent(down);
        } finally {
            down.recycle();
        }

        target.postDelayed(() -> {
            if (isFinishing() || rendererGone || target != webView) return;
            long eventTime = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0);
            try {
                target.dispatchTouchEvent(up);
            } finally {
                up.recycle();
            }
        }, 90L);
    }

    private String createRunnerUrl(String localFileName, String title,
                                   boolean allowNetworking, boolean allowScriptAccess) {
        Uri uri = new Uri.Builder()
                .scheme("https")
                .authority(ASSET_HOST)
                .appendPath("assets")
                .appendPath("runner.html")
                .appendQueryParameter("swf", "/games/" + localFileName)
                .appendQueryParameter("title", title)
                .appendQueryParameter("networking", allowNetworking ? "all" : "internal")
                .appendQueryParameter("scriptAccess", Boolean.toString(allowScriptAccess))
                .appendQueryParameter("quality", settings.quality())
                .appendQueryParameter("scale", settings.scaleMode())
                .appendQueryParameter("gameScale", Float.toString(settings.gameScale()))
                .appendQueryParameter("safeMargin", Integer.toString(settings.safeMarginPercent()))
                .build();
        return uri.toString();
    }

    private void addControls() {
        TextView exit = findViewById(R.id.exit_button);
        styleOverlay(exit);
        exit.setText("EXIT");
        exit.setTextSize(10);
        exit.setContentDescription("Exit game");
        exit.setOnClickListener(v -> finish());

        controlViews = new TextView[SettingsStore.CONTROL_NAMES.length];
        for (int i = 0; i < SettingsStore.CONTROL_NAMES.length; i++) {
            final int index = i;
            TextView button = findViewById(CONTROL_VIEW_IDS[i]);
            styleOverlay(button);
            button.setText(CONTROL_LABELS[i]);
            button.setContentDescription(SettingsStore.CONTROL_NAMES[i] + " directional key");
            button.setVisibility(settings.controlsVisible() ? View.VISIBLE : View.GONE);
            bindKeyButton(button, CONTROL_KEYS[index]);
            controlViews[i] = button;
        }

        actionButton = findViewById(R.id.action_button);
        styleOverlay(actionButton);
        actionButton.setText("A");
        actionButton.setContentDescription("Action (Spacebar)");
        actionButton.setVisibility(settings.controlsVisible() ? View.VISIBLE : View.GONE);
        bindKeyButton(actionButton, KeyEvent.KEYCODE_SPACE);

        controlsLayer.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                 oldLeft, oldTop, oldRight, oldBottom) -> positionControls());
        controlsLayer.post(this::positionControls);
    }

    private void bindKeyButton(TextView button, int keyCode) {
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    userTouchedGame = true;
                    view.setAlpha(0.9f);
                    dispatchGameKey(keyCode, KeyEvent.ACTION_DOWN);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setAlpha(settings.controlsVisible() ? 0.68f : 0f);
                    dispatchGameKey(keyCode, KeyEvent.ACTION_UP);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void positionControls() {
        if (controlsLayer == null || controlViews == null || actionButton == null) return;
        int width = controlsLayer.getWidth();
        int height = controlsLayer.getHeight();
        if (width <= 0 || height <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < controlViews.length; i++) {
            String name = SettingsStore.CONTROL_NAMES[i];
            double requestedPixels = settings.controlSize(name) * settings.overlayScale() * density;
            int size = (int) Math.max(1d, Math.min(Integer.MAX_VALUE, Math.round(requestedPixels)));
            int centerX = Math.round(width * settings.controlX(name) / 100f);
            int centerY = Math.round(height * settings.controlY(name) / 100f);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
            params.leftMargin = centerX - size / 2;
            params.topMargin = centerY - size / 2;
            controlViews[i].setLayoutParams(params);
        }

        int actionSize = (int) Math.max(1d, Math.min(Integer.MAX_VALUE,
                Math.round(64f * settings.overlayScale() * density)));
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(actionSize, actionSize);
        actionParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        actionParams.rightMargin = dp(18);
        actionParams.bottomMargin = dp(22);
        actionButton.setLayoutParams(actionParams);
    }

    private void styleOverlay(TextView view) {
        view.setTextColor(Color.WHITE);
        view.setTextSize(21);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackground(roundOverlay());
        view.setAlpha(0.68f);
        view.setClickable(true);
        view.setFocusable(false);
    }

    private android.graphics.drawable.GradientDrawable roundOverlay() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        drawable.setColor(0xAA26333F);
        drawable.setStroke(dp(1), 0xCC79E2A8);
        return drawable;
    }

    private void dispatchGameKey(int keyCode, int action) {
        if (webView == null || rendererGone) return;
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, 0);
        webView.dispatchKeyEvent(event);
    }

    private void showRecoverableMessage(String message) {
        if (isFinishing()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showFatalMessage(String message) {
        if (root == null || isFinishing()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        if (status == null) {
            status = new TextView(this);
        }
        if (status.getParent() != null) ((ViewGroup) status.getParent()).removeView(status);
        status.setText(message + "\n\nUse Back to return to your library.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void setImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class LocalAssetPathHandler implements WebViewAssetLoader.PathHandler {
        private final AssetManager assets;

        LocalAssetPathHandler(AssetManager assets) {
            this.assets = assets;
        }

        @Override
        public WebResourceResponse handle(String path) {
            if (path == null || path.startsWith("/") || path.contains("..")) return null;
            String lower = path.toLowerCase(Locale.ROOT);
            String mimeType;
            String encoding;
            if (lower.endsWith(".wasm")) {
                mimeType = "application/wasm";
                encoding = null;
            } else if (lower.endsWith(".html")) {
                mimeType = "text/html";
                encoding = "UTF-8";
            } else if (lower.endsWith(".js")) {
                mimeType = "application/javascript";
                encoding = "UTF-8";
            } else {
                String extension = MimeTypeMap.getFileExtensionFromUrl(lower);
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mimeType == null) mimeType = "application/octet-stream";
                encoding = null;
            }
            try {
                InputStream stream = assets.open(path, AssetManager.ACCESS_STREAMING);
                return new WebResourceResponse(mimeType, encoding, stream);
            } catch (IOException error) {
                return null;
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setImmersiveMode();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (controlViews != null) {
            for (int i = 0; i < controlViews.length; i++) {
                controlViews[i].setVisibility(settings.controlsVisible() ? View.VISIBLE : View.GONE);
            }
        }
        if (actionButton != null) {
            actionButton.setVisibility(settings.controlsVisible() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        if (controlsLayer != null) {
            controlsLayer.setForwardingTarget(null);
        }
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.removeJavascriptInterface("FlashDiagnostics");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        io.shutdown();
        super.onDestroy();
    }
}
