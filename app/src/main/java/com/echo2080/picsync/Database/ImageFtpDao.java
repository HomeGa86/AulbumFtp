package com.echo2080.picsync.Database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface ImageFtpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ImageFtpEntity entity);

    // 返回值改为 LiveData<String>，Room 会自动异步查询
    @Query("SELECT ftpPath FROM image_ftp_paths WHERE localUri = :localUri")
    LiveData<String> getFtpPath(String localUri);

    @Query("SELECT localUri FROM image_ftp_paths WHERE ftpPath = :ftpPath")
    String getLocalUriByFtpPath(String ftpPath);

    @Query("DELETE FROM image_ftp_paths WHERE ftpPath = :ftpPath")
    void deleteByFtpPath(String ftpPath);
}