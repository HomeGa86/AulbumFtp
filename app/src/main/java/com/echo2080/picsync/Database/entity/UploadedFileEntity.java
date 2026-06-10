package com.echo2080.picsync.Database.entity;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "uploaded_files",
        indices = {@Index(value = "fileName", unique = true)})
public class UploadedFileEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String fileName; // 仅存储文件名（含后缀），例如 image.jpg
    public long uploadTime; // 上传时间戳

    public UploadedFileEntity(String fileName, long uploadTime) {
        this.fileName = fileName;
        this.uploadTime = uploadTime;
    }
}
