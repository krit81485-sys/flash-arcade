package com.flasharcade.launcher.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.flasharcade.launcher.R;
import com.flasharcade.launcher.data.GameDao;
import com.flasharcade.launcher.data.GameDatabase;
import com.flasharcade.launcher.data.GameEntity;
import com.flasharcade.launcher.storage.CoverStore;
import com.flasharcade.launcher.storage.GameImporter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity implements GameAdapter.Listener {
    private static final String PREFS = "flash_arcade_library";
    private static final String TREE_URI = "library_tree_uri";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private GameDao dao;
    private GameAdapter adapter;
    private TextView status;
    private TextView emptyState;
    private Uri pendingCoverGameUri;
    private ActivityResultLauncher<String[]> filePicker;
    private ActivityResultLauncher<Uri> folderPicker;
    private ActivityResultLauncher<String[]> coverPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dao = GameDatabase.get(this).gameDao();
        registerPickers();
        setContentView(createContent());
        loadGames();
        scanSavedFolder();
    }

    private void registerPickers() {
        filePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The app's private copy is enough after a successful import.
            }
            importSingle(uri, readDisplayName(uri));
        });
        folderPicker = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(TREE_URI, uri.toString()).apply();
                scanFolder(uri, true);
            } catch (SecurityException error) {
                showMessage("Folder access was not granted. Choose the folder again.");
            }
        });
        coverPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || pendingCoverGameUri == null) return;
            Uri selectedGameUri = pendingCoverGameUri;
            pendingCoverGameUri = null;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The image is copied immediately into the app's private covers directory.
            }
            replaceCover(selectedGameUri, uri);
        });
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 20, 25));
        root.setPadding(dp(18), dp(14), dp(18), dp(12));

        TextView eyebrow = text("FLASH ARCADE  /  SWF LIBRARY", 12, 0xFF79E2A8, Typeface.BOLD);
        root.addView(eyebrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        TextView heading = text("Your games", 30, Color.WHITE, Typeface.BOLD);
        root.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button addButton = button("Add SWF");
        Button scanButton = button("Scan folder");
        Button settingsButton = button("Settings");
        addButton.setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));
        scanButton.setOnClickListener(v -> folderPicker.launch(null));
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        actions.addView(addButton, weightedButtonParams());
        actions.addView(scanButton, weightedButtonParams());
        actions.addView(settingsButton, weightedButtonParams());
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        status = text("Choose a SWF file or scan a folder to get started.", 13, 0xFFAAB5C0, Typeface.NORMAL);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        FrameLayoutWithEmpty holder = new FrameLayoutWithEmpty(this);
        adapter = new GameAdapter(this);
        RecyclerView list = new RecyclerView(this);
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(12));
        int widthDp = getResources().getConfiguration().screenWidthDp;
        list.setLayoutManager(new GridLayoutManager(this, widthDp >= 720 ? 3 : 2));
        list.setAdapter(adapter);
        holder.addView(list, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyState = text("No games in your library yet.\nYour imported games stay on this device.", 16,
                0xFF77838F, Typeface.NORMAL);
        emptyState.setGravity(Gravity.CENTER);
        holder.addView(emptyState, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void loadGames() {
        io.execute(() -> {
            List<GameEntity> games = dao.getAll();
            runOnUiThread(() -> showGames(games));
        });
    }

    private void showGames(List<GameEntity> games) {
        if (isFinishing()) return;
        adapter.setGames(games);
        emptyState.setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
        if (!games.isEmpty()) {
            status.setText(games.size() + (games.size() == 1 ? " game in your library" : " games in your library"));
        }
    }

    private void scanSavedFolder() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = preferences.getString(TREE_URI, null);
        if (saved == null) return;
        try {
            scanFolder(Uri.parse(saved), false);
        } catch (RuntimeException error) {
            preferences.edit().remove(TREE_URI).apply();
            showMessage("The saved folder is no longer available. Choose it again to rescan.");
        }
    }

    private void scanFolder(Uri treeUri, boolean announce) {
        status.setText("Scanning folder for SWF games…");
        io.execute(() -> {
            int imported = 0;
            int failed = 0;
            try {
                GameImporter importer = new GameImporter(this, dao);
                Set<String> existing = new HashSet<>();
                for (GameEntity game : dao.getAll()) existing.add(game.sourceUri);
                DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
                if (root == null || !root.isDirectory()) throw new IOException("That folder could not be opened.");
                ArrayDeque<DocumentFile> pending = new ArrayDeque<>();
                pending.add(root);
                while (!pending.isEmpty()) {
                    DocumentFile directory = pending.removeFirst();
                    DocumentFile[] children = directory.listFiles();
                    for (DocumentFile child : children) {
                        if (child.isDirectory()) {
                            pending.addLast(child);
                        } else if (child.isFile() && child.getName() != null
                                && child.getName().toLowerCase(Locale.ROOT).endsWith(".swf")
                                && !existing.contains(child.getUri().toString())) {
                            try {
                                importer.importSwf(child.getUri(), child.getName());
                                existing.add(child.getUri().toString());
                                imported++;
                            } catch (IOException | SecurityException error) {
                                failed++;
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException error) {
                failed++;
            }
            int added = imported;
            int errors = failed;
            runOnUiThread(() -> {
                loadGames();
                if (announce || added > 0 || errors > 0) {
                    String message = added + (added == 1 ? " game added" : " games added");
                    if (errors > 0) message += " · " + errors + " could not be read";
                    status.setText(message);
                } else {
                    status.setText("Folder scan complete. No new SWF files were found.");
                }
            });
        });
    }

    private void importSingle(Uri uri, String displayName) {
        status.setText("Importing game…");
        io.execute(() -> {
            try {
                GameImporter importer = new GameImporter(this, dao);
                GameEntity game = importer.importSwf(uri, displayName);
                runOnUiThread(() -> {
                    loadGames();
                    status.setText("Ready to play: " + game.title);
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> showMessage(error.getMessage() == null
                        ? "The SWF could not be imported." : error.getMessage()));
            }
        });
    }

    private String readDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null) return name;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the last path segment.
        }
        String last = uri.getLastPathSegment();
        return last == null ? "game.swf" : last;
    }

    @Override
    public void onLaunch(GameEntity game) {
        launchGame(game, false, false);
    }

    @Override
    public void onOpenMenu(GameEntity game) {
        String[] options = {
                "Change cover",
                "Rename",
                "Remove from library",
                "Test with extra Ruffle permissions"
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(game.title)
                .setItems(options, (dialog, index) -> {
                    if (index == 0) {
                        pendingCoverGameUri = Uri.parse(game.sourceUri);
                        coverPicker.launch(new String[]{"image/*"});
                    } else if (index == 1) {
                        renameGame(game);
                    } else if (index == 2) {
                        confirmDelete(game);
                    } else if (index == 3) {
                        confirmPermissionTest(game);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmPermissionTest(GameEntity game) {
        View content = getLayoutInflater().inflate(
                R.layout.dialog_permission_test, null, false);
        CheckBox networkingCheckbox = content.findViewById(R.id.permission_allow_networking);
        CheckBox scriptAccessCheckbox = content.findViewById(R.id.permission_allow_script_access);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Test extra access for this game?")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Launch test", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    boolean allowNetworking = networkingCheckbox.isChecked();
                    boolean allowScriptAccess = scriptAccessCheckbox.isChecked();
                    if (!allowNetworking && !allowScriptAccess) {
                        showMessage("Choose at least one permission for the test.");
                        return;
                    }
                    dialog.dismiss();
                    launchGame(game, allowNetworking, allowScriptAccess);
                }));
        dialog.show();
    }

    private void launchGame(GameEntity game, boolean allowNetworking, boolean allowScriptAccess) {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_GAME_ID, game.id);
        intent.putExtra(PlayerActivity.EXTRA_ALLOW_NETWORKING, allowNetworking);
        intent.putExtra(PlayerActivity.EXTRA_ALLOW_SCRIPT_ACCESS, allowScriptAccess);
        startActivity(intent);
    }

    private void renameGame(GameEntity game) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(game.title);
        input.setSelection(input.length());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rename game")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (title.isEmpty()) {
                        showMessage("Enter a game name.");
                        return;
                    }
                    io.execute(() -> {
                        game.title = title;
                        dao.update(game);
                        runOnUiThread(this::loadGames);
                    });
                })
                .show();
    }

    private void confirmDelete(GameEntity game) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Remove game?")
                .setMessage("This removes the app's private copy and cover. The original file will not be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> io.execute(() -> {
                    File gameFile = GameImporter.gameFile(this, game.localFileName);
                    if (gameFile.isFile()) gameFile.delete();
                    CoverStore.delete(game.coverPath);
                    dao.delete(game);
                    runOnUiThread(this::loadGames);
                }))
                .show();
    }

    private void replaceCover(Uri gameUri, Uri imageUri) {
        io.execute(() -> {
            try {
                GameEntity game = dao.getBySourceUri(gameUri.toString());
                if (game == null) throw new IOException("That game is no longer in the library.");
                CoverStore store = new CoverStore(this);
                String key = new File(game.coverPath).getName().replaceFirst("\\.png$", "");
                File cover = store.replace(key, imageUri, getContentResolver());
                game.coverPath = cover.getAbsolutePath();
                dao.update(game);
                runOnUiThread(() -> {
                    loadGames();
                    showMessage("Cover updated.");
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> showMessage(error.getMessage() == null
                        ? "The cover image could not be saved." : error.getMessage()));
            }
        });
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private TextView text(String value, int sizeSp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(roundRect(0xFF26333F, 12));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.setMargins(0, 0, dp(6), 0);
        return params;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class FrameLayoutWithEmpty extends android.widget.FrameLayout {
        FrameLayoutWithEmpty(MainActivity context) {
            super(context);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
