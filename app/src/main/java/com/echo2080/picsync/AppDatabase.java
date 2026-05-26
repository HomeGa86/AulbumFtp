package com.echo2080.picsync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                ImageFtpEntity.class,
                DownloadedFileEntity.class,
                ServerFileEntity.class
        },
        version = 5,
        exportSchema = false
)
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {

    public abstract ImageFtpDao imageFtpDao();
    public abstract DownloadedFileDao downloadedFileDao();
    public abstract ServerFileDao serverFileDao(); // 3. 添加新 DAO


    private static volatile AppDatabase INSTANCE;

    // 保留原本的 2->3 迁移脚本，确保更老版本的使用者能正常连续升级
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE downloaded_files ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0");
        }
    };

    // ⬅️ 3. 新增 3->4 迁移脚本：为表增加 fileType 字段，默认值填 'PICTURE'
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 注意：SQL 语句里的 'PICTURE' 必须与枚举的定义完全一致（大写，单引号包裹）
            database.execSQL("ALTER TABLE downloaded_files "
                    + " ADD COLUMN fileType TEXT NOT NULL DEFAULT 'PICTURE'");
        }
    };

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `server_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `filePath` TEXT)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_server_files_filePath` ON `server_files` (`filePath`)");
        }
    };


    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "pic_sync_db"
                    )
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5) // 5. 注册新迁移脚本
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}