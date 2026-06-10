package com.echo2080.picsync.Database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull; // ⬅️ 必须导入这个包


@Entity(tableName = "image_ftp_paths")
public class ImageFtpEntity {
    @PrimaryKey
    @NonNull
    private String localUri;  // 本地缩略图的 URI
    private String ftpPath;   // FTP 上的完整路径

    public ImageFtpEntity(String localUri, String ftpPath) {
        this.localUri = localUri;
        this.ftpPath = ftpPath;
    }

    @NonNull
    public String getLocalUri() { return localUri; }
    public String getFtpPath() { return ftpPath; }
}