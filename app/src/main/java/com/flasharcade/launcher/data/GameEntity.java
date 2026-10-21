package com.flasharcade.launcher.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "games",
        indices = {@Index(value = {"sourceUri"}, unique = true)}
)
public class GameEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String title = "Untitled game";

    @NonNull
    public String sourceUri = "";

    @NonNull
    public String localFileName = "";

    @NonNull
    public String coverPath = "";

    public long addedAt;
    public long lastPlayedAt;
    public int launchCount;
}
