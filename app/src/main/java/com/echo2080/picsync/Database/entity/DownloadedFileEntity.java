package com.echo2080.picsync.Database.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import com.echo2080.picsync.model.FileType;

@Entity(tableName = "downloaded_files")
public class DownloadedFileEntity {

    @PrimaryKey
    @NonNull
    private String ftpPath;  // FTP上的完整路径，作为主键

    private String localThumbnailPath; // 本地缩略图路径
    private long downloadTime;         // 下载时间戳
    private long captureTime;          // 图片拍摄时间戳
    private int isDeleted = 0;

    // 【修改】类型改为FileType枚举，默认值为"PICTURE"
    @NonNull
    @ColumnInfo(defaultValue = "PICTURE")
    private FileType fileType = FileType.PICTURE;

    // 【修改】构造函数，接收FileType枚举
    public DownloadedFileEntity(@NonNull String ftpPath, String localThumbnailPath,
                                long downloadTime, long captureTime, @NonNull FileType fileType) {
        this.ftpPath = ftpPath;
        this.localThumbnailPath = localThumbnailPath;
        this.downloadTime = downloadTime;
        this.captureTime = captureTime;
        this.isDeleted = 0;
        this.fileType = fileType;
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

    public long getCaptureTime() {
        return captureTime;
    }

    public void setCaptureTime(long captureTime) {
        this.captureTime = captureTime;
    }


    public int getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(int isDeleted) {
        this.isDeleted = isDeleted;
    }

    // 【修改】Getter和Setter的类型
    @NonNull
    public FileType getFileType() {
        return fileType;
    }

    public void setFileType(@NonNull FileType fileType) {
        this.fileType = fileType;
    }
}
