package com.flasharcade.launcher.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.flasharcade.launcher.util.SettingsStore;

import java.util.Locale;

public class SettingsActivity extends Activity {
    private SettingsStore settings;
    private PreviewView preview;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SettingsStore(this);
        density = getResources().getDisplayMetrics().density;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF101419);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView title = text("Player settings", 23, Color.WHITE, Typeface.BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button done = button("Done");
        done.setOnClickListener(v -> finish());
        topBar.addView(done, new LinearLayout.LayoutParams(dp(82), dp(42)));
        page.addView(topBar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(28));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        sectionTitle(content, "Live preview");
        preview = new PreviewView(this, settings);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(224));
        previewParams.bottomMargin = dp(8);
        content.addView(preview, previewParams);
        hint(content, "The preview updates as you change game bounds, scaling, and each touch key.");

        sectionTitle(content, "Game display");
        addScaleEditor(content, "Game view scale", settings.gameScale(), settings::setGameScale);
        addSeekEditor(content, "Safe edge margin", 0, 30, settings.safeMarginPercent(),
                value -> settings.setSafeMarginPercent(value));
        addSpinner(content, "Stage quality at launch",
                new String[]{"High", "Medium", "Low"},
                new String[]{SettingsStore.QUALITY_HIGH, SettingsStore.QUALITY_MEDIUM, SettingsStore.QUALITY_LOW},
                settings.quality(), settings::setQuality);
        hint(content, "Quality sets Ruffle's starting Stage.quality. A game may change its own quality later.");
        addSpinner(content, "Stage scale mode",
                new String[]{"Show all (keep aspect ratio)", "No border (fill screen)", "Exact fit (stretch)", "No scale"},
                new String[]{SettingsStore.SCALE_SHOW_ALL, SettingsStore.SCALE_NO_BORDER,
                        SettingsStore.SCALE_EXACT_FIT, SettingsStore.SCALE_NO_SCALE},
                settings.scaleMode(), settings::setScaleMode);

        sectionTitle(content, "Touch controls");
        CheckBox visible = new CheckBox(this);
        visible.setText("Show independent directional buttons");
        visible.setTextColor(Color.WHITE);
        visible.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF79E2A8));
        visible.setChecked(settings.controlsVisible());
        visible.setOnCheckedChangeListener((buttonView, checked) -> {
            settings.setControlsVisible(checked);
            preview.invalidate();
        });
        content.addView(visible, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        addScaleEditor(content, "All touch controls scale", settings.overlayScale(), settings::setOverlayScale);

        for (String name : SettingsStore.CONTROL_NAMES) {
            sectionTitle(content, name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1) + " button");
            addSeekEditor(content, "Horizontal position", 0, 100, settings.controlX(name), value -> {
                settings.setControlX(name, value);
                preview.invalidate();
            });
            addSeekEditor(content, "Vertical position", 0, 100, settings.controlY(name), value -> {
                settings.setControlY(name, value);
                preview.invalidate();
            });
            addSizeEditor(content, name);
        }

        setContentView(page);
    }

    private void addScaleEditor(LinearLayout parent, String label, float initial, FloatConsumer consumer) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 14, 0xFFD7DEE5, Typeface.BOLD);
        row.addView(labelView, new LinearLayout.LayoutParams(0, dp(42), 1f));
        EditText number = numericField(initial);
        row.addView(number, new LinearLayout.LayoutParams(dp(94), dp(42)));
        parent.addView(row);

        SeekBar quick = new SeekBar(this);
        quick.setMax(275);
        quick.setProgress(scaleToProgress(initial));
        quick.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF79E2A8));
        quick.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFF79E2A8));
        parent.addView(quick, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        hint(parent, "Quick range: 0.25× to 3.00×. Enter any positive scale in the field for larger or smaller values.");

        final boolean[] syncing = {false};
        quick.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float value = (progress + 25) / 100f;
                syncing[0] = true;
                number.setText(format(value));
                syncing[0] = false;
                consumer.accept(value);
                preview.invalidate();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        number.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (syncing[0]) return;
                Float value = parsePositive(s);
                if (value == null) return;
                consumer.accept(value);
                syncing[0] = true;
                quick.setProgress(scaleToProgress(value));
                syncing[0] = false;
                preview.invalidate();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void addSizeEditor(LinearLayout parent, String controlName) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("Button size (dp)", 13, 0xFFABB6C1, Typeface.NORMAL);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(40), 1f));
        EditText number = numericField(settings.controlSize(controlName));
        row.addView(number, new LinearLayout.LayoutParams(dp(94), dp(40)));
        parent.addView(row);

        SeekBar quick = new SeekBar(this);
        quick.setMax(136);
        quick.setProgress(Math.max(0, Math.min(136, Math.round(settings.controlSize(controlName)) - 24)));
        quick.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF79E2A8));
        quick.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFF79E2A8));
        parent.addView(quick, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        hint(parent, "Independent size. The field has no app-defined upper limit.");

        final boolean[] syncing = {false};
        quick.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float value = progress + 24f;
                syncing[0] = true;
                number.setText(format(value));
                syncing[0] = false;
                settings.setControlSize(controlName, value);
                preview.invalidate();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        number.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (syncing[0]) return;
                Float value = parsePositive(s);
                if (value == null) return;
                settings.setControlSize(controlName, value);
                syncing[0] = true;
                quick.setProgress(Math.max(0, Math.min(136, Math.round(value) - 24)));
                syncing[0] = false;
                preview.invalidate();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void addSeekEditor(LinearLayout parent, String label, int min, int max, int initial, IntConsumer consumer) {
        TextView labelView = text(label + "  ·  " + initial + "%", 13, 0xFFABB6C1, Typeface.NORMAL);
        parent.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - min);
        seekBar.setProgress(Math.max(0, Math.min(max - min, initial - min)));
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF79E2A8));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFF79E2A8));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = min + progress;
                labelView.setText(label + "  ·  " + value + "%");
                consumer.accept(value);
                preview.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        parent.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private void addSpinner(LinearLayout parent, String label, String[] labels,
                            String[] values, String selected, StringConsumer consumer) {
        TextView labelView = text(label, 14, 0xFFD7DEE5, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        labelParams.topMargin = dp(4);
        parent.addView(labelView, labelParams);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) index = i;
        }
        spinner.setSelection(index);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parentView, View view, int position, long id) {
                consumer.accept(values[position]);
                preview.invalidate();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parentView) {}
        });
        parent.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private EditText numericField(float value) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextColor(Color.WHITE);
        edit.setTextSize(14);
        edit.setGravity(Gravity.CENTER);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        edit.setBackground(background(0xFF202A34, 9));
        edit.setPadding(dp(5), 0, dp(5), 0);
        edit.setText(format(value));
        return edit;
    }

    private Float parsePositive(CharSequence text) {
        if (text == null || text.length() == 0) return null;
        try {
            float value = Float.parseFloat(text.toString());
            return value > 0f && !Float.isNaN(value) && !Float.isInfinite(value) ? value : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private int scaleToProgress(float value) {
        return Math.max(0, Math.min(275, Math.round(value * 100f) - 25));
    }

    private String format(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private void sectionTitle(LinearLayout parent, String title) {
        TextView view = text(title, 18, 0xFF79E2A8, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        params.topMargin = dp(9);
        parent.addView(view, params);
    }

    private void hint(LinearLayout parent, String value) {
        TextView view = text(value, 11, 0xFF82909D, Typeface.NORMAL);
        view.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        params.bottomMargin = dp(8);
        parent.addView(view, params);
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(background(0xFF26333F, 10));
        return button;
    }

    private GradientDrawable background(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private interface FloatConsumer { void accept(float value); }
    private interface IntConsumer { void accept(int value); }
    private interface StringConsumer { void accept(String value); }
}
