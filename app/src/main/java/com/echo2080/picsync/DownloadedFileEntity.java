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
    private long captureTime;          // 【新增】图片拍摄时间戳
    private int isDeleted = 0;


    // 【修改】构造函数增加 captureTime 参数
    public DownloadedFileEntity(@NonNull String ftpPath, String localThumbnailPath, long downloadTime, long captureTime) {
        this.ftpPath = ftpPath;
        this.localThumbnailPath = localThumbnailPath;
        this.downloadTime = downloadTime;
        this.captureTime = captureTime;
        this.isDeleted = 0; // 新插入的记录默认未删除
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

    // 【新增】captureTime 的 Getter 方法
    public long getCaptureTime() {
        return captureTime;
    }

    public int getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(int isDeleted) {
        this.isDeleted = isDeleted;
    }

}