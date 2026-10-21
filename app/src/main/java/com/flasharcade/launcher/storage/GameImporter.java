package com.flasharcade.launcher.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.flasharcade.launcher.data.GameDao;
import com.flasharcade.launcher.data.GameEntity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

public final class GameImporter {
    private final Context context;
    private final GameDao dao;
    private final File gamesDirectory;
    private final CoverStore coverStore;

    public GameImporter(Context context, GameDao dao) throws IOException {
        this.context = context.getApplicationContext();
        this.dao = dao;
        gamesDirectory = new File(this.context.getFilesDir(), "games");
        if (!gamesDirectory.exists() && !gamesDirectory.mkdirs()) {
            throw new IOException("Could not create the game storage directory.");
        }
        if (!gamesDirectory.isDirectory()) throw new IOException("The game storage path is not a directory.");
        coverStore = new CoverStore(this.context);
    }

    public GameEntity importSwf(Uri source, String displayName) throws IOException {
        String sourceString = source.toString();
        GameEntity existing = dao.getBySourceUri(sourceString);
        if (existing != null) return existing;
        if (!displayName.toLowerCase(Locale.ROOT).endsWith(".swf")) {
            throw new IOException("Choose a file with the .swf extension.");
        }

        String key = UUID.randomUUID().toString();
        String localName = key + ".swf";
        File destination = new File(gamesDirectory, localName);
        File temporary = new File(gamesDirectory, localName + ".part");
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) throw new IOException("The selected SWF could not be opened.");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            output.getFD().sync();
        } catch (IOException | RuntimeException error) {
            temporary.delete();
            throw error;
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not save the game in app storage.");
        }

        String title = cleanTitle(displayName);
        File cover = null;
        try {
            cover = coverStore.createDefault(key, title);
            GameEntity game = new GameEntity();
            game.title = title;
            game.sourceUri = sourceString;
            game.localFileName = localName;
            game.coverPath = cover.getAbsolutePath();
            game.addedAt = System.currentTimeMillis();
            long id = dao.insert(game);
            if (id < 0) {
                destination.delete();
                if (cover != null) cover.delete();
                GameEntity duplicate = dao.getBySourceUri(sourceString);
                if (duplicate != null) return duplicate;
                throw new IOException("The game could not be added to the library.");
            }
            game.id = id;
            return game;
        } catch (IOException | RuntimeException error) {
            destination.delete();
            if (cover != null) cover.delete();
            throw error;
        }
    }

    public static String cleanTitle(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return "Untitled game";
        String title = fileName.trim();
        if (title.toLowerCase(Locale.ROOT).endsWith(".swf")) {
            title = title.substring(0, title.length() - 4);
        }
        return title.isEmpty() ? "Untitled game" : title;
    }

    public static File gameFile(Context context, String localFileName) {
        return new File(new File(context.getFilesDir(), "games"), localFileName);
    }
}
