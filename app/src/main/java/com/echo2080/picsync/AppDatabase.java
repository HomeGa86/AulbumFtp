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
        version = 2,  // ⬅️ 升级版本号
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract ImageFtpDao imageFtpDao();
    public abstract DownloadedFileDao downloadedFileDao(); // ⬅️ 添加新 DAO

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "pic_sync_db"
                    )
                            .fallbackToDestructiveMigration() // 开发阶段方便，生产环境建议用 Migration
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}