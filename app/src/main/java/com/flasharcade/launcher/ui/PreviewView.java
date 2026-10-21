package com.flasharcade.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.flasharcade.launcher.util.SettingsStore;

public class PreviewView extends View {
    private final SettingsStore settings;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] labels = {"↑", "↓", "←", "→"};

    public PreviewView(Context context, SettingsStore settings) {
        super(context);
        this.settings = settings;
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF070A0D);
        background.setCornerRadius(dp(15));
        setBackground(background);
        setClipToOutline(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float margin = Math.min(width, height) * settings.safeMarginPercent() / 100f;
        RectF safeBounds = new RectF(margin, margin, width - margin, height - margin);
        paint.setColor(0xFF151E27);
        canvas.drawRoundRect(safeBounds, dp(9), dp(9), paint);

        float baseWidth = safeBounds.width() * 0.9f;
        float baseHeight = safeBounds.height() * 0.78f;
        float gameScale = settings.gameScale();
        float scaledWidth = safeDimension(baseWidth * gameScale);
        float scaledHeight = safeDimension(baseHeight * gameScale);
        float centerX = safeBounds.centerX();
        float centerY = safeBounds.centerY();
        RectF stage = new RectF(centerX - scaledWidth / 2f, centerY - scaledHeight / 2f,
                centerX + scaledWidth / 2f, centerY + scaledHeight / 2f);

        int checkpoint = canvas.save();
        canvas.clipRect(safeBounds);
        paint.setColor(0xFF263B4D);
        canvas.drawRoundRect(stage, dp(7), dp(7), paint);
        paint.setColor(0xFF344E63);
        RectF stageInner = new RectF(stage.left + dp(6), stage.top + dp(6),
                stage.right - dp(6), stage.bottom - dp(6));
        canvas.drawRoundRect(stageInner, dp(5), dp(5), paint);

        paint.setColor(0xFF79E2A8);
        paint.setTextSize(dp(11));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("GAME STAGE  ·  " + settings.quality().toUpperCase(), centerX, centerY, paint);
        canvas.restoreToCount(checkpoint);

        if (!settings.controlsVisible()) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(11));
            paint.setColor(0xFF82909D);
            canvas.drawText("Touch controls hidden", width / 2f, height - dp(12), paint);
            return;
        }

        for (int i = 0; i < SettingsStore.CONTROL_NAMES.length; i++) {
            String name = SettingsStore.CONTROL_NAMES[i];
            float x = width * settings.controlX(name) / 100f;
            float y = height * settings.controlY(name) / 100f;
            float radius = safeDimension(dp(settings.controlSize(name) * settings.overlayScale())) / 2f;
            int buttonSave = canvas.save();
            canvas.clipRect(0, 0, width, height);
            paint.setColor(0xBB26333F);
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(0xFF79E2A8);
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(Math.min(radius, dp(18)));
            float baseline = y - (paint.ascent() + paint.descent()) / 2f;
            canvas.drawText(labels[i], x, baseline, paint);
            canvas.restoreToCount(buttonSave);
        }
    }

    private float safeDimension(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return Math.max(getWidth(), getHeight()) * 4f;
        return Math.max(dp(1), Math.min(value, Math.max(getWidth(), getHeight()) * 4f));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
