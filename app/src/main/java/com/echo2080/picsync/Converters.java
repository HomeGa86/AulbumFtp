package site.rossiluo.picsync;

import androidx.room.TypeConverter;

public class Converters {

    // 1. 存入数据库时：把 Java 的 FileType 枚举对象，转换成 SQLite 认识的字符串 (String)
    @TypeConverter
    public static String fromFileType(FileType fileType) {
        return fileType == null ? FileType.PICTURE.name() : fileType.name();
    }

    // 2. 从数据库读取时：把 SQLite 里的字符串 (String)，转换回 Java 的 FileType 枚举对象
    @TypeConverter
    public static FileType toFileType(String value) {
        if (value == null) {
            return FileType.PICTURE;
        }
        try {
            // valueOf 会把 "PICTURE" 转成 FileType.PICTURE
            return FileType.valueOf(value);
        } catch (IllegalArgumentException e) {
            // 防御性代码：如果数据库里有无法识别的脏数据，默认当做图片处理
            return FileType.PICTURE;
        }
    }
}