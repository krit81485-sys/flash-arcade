package com.flasharcade.launcher.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface GameDao {
    @Query("SELECT * FROM games ORDER BY lastPlayedAt DESC, addedAt DESC")
    List<GameEntity> getAll();

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    GameEntity getById(long id);

    @Query("SELECT * FROM games WHERE sourceUri = :uri LIMIT 1")
    GameEntity getBySourceUri(String uri);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(GameEntity game);

    @Update
    void update(GameEntity game);

    @Delete
    void delete(GameEntity game);
}
