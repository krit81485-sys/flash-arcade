package com.flasharcade.launcher.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {GameEntity.class}, version = 1, exportSchema = false)
public abstract class GameDatabase extends RoomDatabase {
    private static volatile GameDatabase instance;

    public abstract GameDao gameDao();

    public static GameDatabase get(Context context) {
        GameDatabase local = instance;
        if (local == null) {
            synchronized (GameDatabase.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    GameDatabase.class,
                                    "flash-arcade.db"
                            )
                            .build();
                    instance = local;
                }
            }
        }
        return local;
    }
}
