package com.echo2080.picsync;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "downloaded_files")
public class DownloadedFileEntity {

    @PrimaryKey
    @NonNull
    private String ftpPath;  // FTP 上的完整路径，作为主键

    private String localThumbnailPath; // 本地缩略图路径
    private long downloadTime;         // 下载时间戳

    public DownloadedFileEntity(@NonNull String ftpPath, String localThumbnailPath, long downloadTime) {
        this.ftpPath = ftpPath;
        this.localThumbnailPath = localThumbnailPath;
        this.downloadTime = downloadTime;
    }

    @NonNull
    public String getFtpPath() {
        return ftpPath;
    }

    public String getLocalThumbnailPath() {
        return localThumbnailPath;
    }

    public long getDownloadTime() {
        return downloadTime;
    }
}