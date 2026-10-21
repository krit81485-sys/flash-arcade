package com.flasharcade.launcher.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public final class CoverStore {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;
    private final File directory;

    public CoverStore(Context context) throws IOException {
        directory = new File(context.getFilesDir(), "covers");
        ensureDirectory(directory);
    }

    public File createDefault(String fileKey, String title) throws IOException {
        File destination = new File(directory, fileKey + ".png");
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int seed = title == null ? 0 : title.toLowerCase(Locale.ROOT).hashCode();
        int first = 0xFF000000 | (0x3F5B75 + (seed & 0x00202020));
        int second = 0xFF000000 | (0x16212C + ((seed >>> 4) & 0x00101010));
        paint.setShader(new LinearGradient(0, 0, WIDTH, HEIGHT, first, second, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, HEIGHT, paint);
        paint.setShader(null);

        paint.setColor(0x5579E2A8);
        canvas.drawCircle(WIDTH * 0.82f, HEIGHT * 0.25f, HEIGHT * 0.34f, paint);
        paint.setColor(0x2279E2A8);
        canvas.drawCircle(WIDTH * 0.82f, HEIGHT * 0.25f, HEIGHT * 0.47f, paint);

        paint.setColor(0xFF79E2A8);
        paint.setTextSize(25);
        paint.setLetterSpacing(0.16f);
        canvas.drawText("FLASH ARCADE  /  SWF", 34, 54, paint);

        String safeTitle = title == null || title.trim().isEmpty() ? "Untitled game" : title.trim();
        paint.setColor(0xFFFFFFFF);
        paint.setTextSize(42);
        paint.setLetterSpacing(0f);
        Rect bounds = new Rect();
        paint.getTextBounds(safeTitle, 0, safeTitle.length(), bounds);
        float maxWidth = WIDTH - 68f;
        float textSize = paint.getTextSize();
        if (bounds.width() > maxWidth) {
            textSize = textSize * maxWidth / bounds.width();
            paint.setTextSize(Math.max(23, textSize));
        }
        canvas.drawText(safeTitle, 34, HEIGHT - 56, paint);
        savePng(bitmap, destination);
        bitmap.recycle();
        return destination;
    }

    public File replace(String fileKey, Uri source, ContentResolver resolver) throws IOException {
        File destination = new File(directory, fileKey + ".png");
        File temporary = new File(directory, fileKey + ".png.part");
        Bitmap sampled = null;
        Bitmap thumbnail = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = resolver.openInputStream(source)) {
                if (input == null) throw new IOException("The selected cover could not be opened.");
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("Choose a valid image file for the cover.");
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = 1;
            while (bounds.outWidth / decode.inSampleSize > WIDTH * 2
                    || bounds.outHeight / decode.inSampleSize > HEIGHT * 2) {
                decode.inSampleSize *= 2;
            }
            try (InputStream input = resolver.openInputStream(source)) {
                if (input == null) throw new IOException("The selected cover could not be opened.");
                sampled = BitmapFactory.decodeStream(input, null, decode);
            }
            if (sampled == null) throw new IOException("The selected cover image could not be decoded.");

            thumbnail = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(thumbnail);
            float scale = Math.max((float) WIDTH / sampled.getWidth(), (float) HEIGHT / sampled.getHeight());
            float drawWidth = sampled.getWidth() * scale;
            float drawHeight = sampled.getHeight() * scale;
            float left = (WIDTH - drawWidth) / 2f;
            float top = (HEIGHT - drawHeight) / 2f;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(sampled, null, new RectF(left, top, left + drawWidth, top + drawHeight), paint);

            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!thumbnail.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Could not save the cover image.");
                }
                output.flush();
            }
        } catch (IOException | RuntimeException error) {
            temporary.delete();
            throw error;
        } finally {
            if (sampled != null && !sampled.isRecycled()) sampled.recycle();
            if (thumbnail != null && !thumbnail.isRecycled()) thumbnail.recycle();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not save the cover image.");
        }
        return destination;
    }

    public static void delete(String path) {
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        if (file.isFile()) file.delete();
    }

    private static void savePng(Bitmap bitmap, File destination) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Could not create cover image.");
            }
            output.flush();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not save generated cover image.");
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create the covers directory.");
        }
        if (!directory.isDirectory()) throw new IOException("The covers path is not a directory.");
    }
}
