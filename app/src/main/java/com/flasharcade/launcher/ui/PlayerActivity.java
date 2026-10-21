package com.flasharcade.launcher.ui;

import android.app.Activity;
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
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {
    public static final String EXTRA_GAME_ID = "game_id";
    private static final String ASSET_HOST = "appassets.androidplatform.net";
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
    private FrameLayout root;
    private TouchPassthroughFrameLayout controlsLayer;
    private WebView webView;
    private TextView status;
    private TextView[] controlViews;
    private TextView actionButton;
    private SettingsStore settings;
    private boolean rendererGone;
    private boolean userTouchedGame;
    private boolean startupTapAttempted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setImmersiveMode();
        settings = new SettingsStore(this);
        setContentView(R.layout.activity_game);
        root = findViewById(R.id.game_root);
        webView = findViewById(R.id.game_web_view);
        controlsLayer = findViewById(R.id.controls_layer);
        status = findViewById(R.id.player_status);
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

        webView.setOnTouchListener((view, event) -> {
            if (!startupTapAttempted && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                userTouchedGame = true;
            }
            return false;
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                int priority = message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                        ? Log.ERROR : Log.DEBUG;
                Log.println(priority, "FlashArcade/Ruffle",
                        message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !("https".equalsIgnoreCase(uri.getScheme()) && ASSET_HOST.equalsIgnoreCase(uri.getHost()));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (status != null && status.getParent() != null) root.removeView(status);
                view.requestFocus();
                view.postDelayed(() -> dispatchInitialCanvasTap(view), 1_500L);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showRecoverableMessage("Ruffle could not load this SWF. Check the imported file and try again.");
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
                return true;
            }
        });
        controlsLayer.setForwardingTarget(webView);
        addControls();
        webView.loadUrl(createRunnerUrl(game.localFileName, game.title));
        webView.requestFocus();
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

    private String createRunnerUrl(String localFileName, String title) {
        Uri uri = new Uri.Builder()
                .scheme("https")
                .authority(ASSET_HOST)
                .appendPath("assets")
                .appendPath("runner.html")
                .appendQueryParameter("swf", "/games/" + localFileName)
                .appendQueryParameter("title", title)
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
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        io.shutdown();
        super.onDestroy();
    }
}
