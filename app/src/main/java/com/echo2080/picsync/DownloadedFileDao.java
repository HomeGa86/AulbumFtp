package com.echo2080.picsync;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DownloadedFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DownloadedFileEntity entity);

    @Query("SELECT * FROM downloaded_files WHERE ftpPath = :ftpPath")
    DownloadedFileEntity getByFtpPath(String ftpPath);

    @Query("SELECT ftpPath FROM downloaded_files")
    List<String> getAllDownloadedPaths();

    @Query("SELECT COUNT(*) FROM downloaded_files")
    int getDownloadedCount();

    @Query("SELECT * FROM downloaded_files")
    List<DownloadedFileEntity> getAllDownloadedFiles();

}