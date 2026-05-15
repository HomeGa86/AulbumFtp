package com.echo2080.picsync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;


@Database(
        entities = {
                ImageFtpEntity.class,
                DownloadedFileEntity.class  // ⬅️ 添加新表
        },
        version = 3,  // ⬅️ 升级版本号
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract ImageFtpDao imageFtpDao();
    public abstract DownloadedFileDao downloadedFileDao(); // ⬅️ 添加新 DAO

    private static volatile AppDatabase INSTANCE;

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // 执行 SQL 语句，给 downloaded_files 表新增 isDeleted 字段
            // 注意：新增的非主键字段必须设置默认值，这里设为 0（代表未删除）
            database.execSQL("ALTER TABLE downloaded_files ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0");
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
                            .addMigrations(MIGRATION_2_3)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}