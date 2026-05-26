// 文件名：ServerFileEntity.java
package site.rossiluo.picsync;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "server_files",
        indices = {@Index(value = "filePath", unique = true)}) // 添加唯一索引，避免重复插入
public class ServerFileEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String filePath; // 存储服务器上的完整文件路径

    public ServerFileEntity(String filePath) {
        this.filePath = filePath;
    }
}