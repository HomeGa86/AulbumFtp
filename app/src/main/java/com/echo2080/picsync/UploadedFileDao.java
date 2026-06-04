package com.echo2080.picsync;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface UploadedFileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(UploadedFileEntity file);

    @Query("SELECT fileName FROM uploaded_files")
    List<String> getAllUploadedFileNames();

    @Query("SELECT COUNT(*) FROM uploaded_files WHERE fileName = :fileName")
    int countByFileName(String fileName);

    @Query("DELETE FROM uploaded_files")
    void deleteAll();
}
