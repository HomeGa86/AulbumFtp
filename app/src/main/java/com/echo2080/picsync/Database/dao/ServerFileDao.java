package com.echo2080.picsync.Database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.echo2080.picsync.Database.entity.ServerFileEntity;

import java.util.List;

@Dao
public interface ServerFileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<ServerFileEntity> files);

    @Query("SELECT filePath FROM server_files")
    List<String> getAllFilePaths();

    @Query("DELETE FROM server_files")
    void deleteAll();
}