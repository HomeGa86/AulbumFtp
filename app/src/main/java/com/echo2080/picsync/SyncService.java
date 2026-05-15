package com.echo2080.picsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncService extends Service {

    private static final String TAG = "SyncService";
    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIFICATION_ID = 1;

    // 新增：用于存储同步状态的 SharedPreferences 键名
    private static final String PREFS_NAME = "SyncServicePrefs";
    private static final String KEY_LAST_FULL_SYNC_TIME = "last_full_sync_time";
    // 10天的毫秒数 (10 * 24 * 60 * 60 * 1000)
    private static final long FULL_SYNC_INTERVAL_MS = 10 * 24 * 60 * 60 * 1000L;

    private AppDatabase database;
    private FtpHelper ftpHelper;
    private ExecutorService executor;
    private Handler mainHandler;
    private AtomicBoolean isRunning = new AtomicBoolean(false);

    // 缩略图保存目录
    private File thumbnailDir;

    public static void resetFullSyncTimestamp(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(KEY_LAST_FULL_SYNC_TIME, 0L); // 重置为0
        editor.apply();
        Log.d(TAG, "FTP配置已变更，已重置完全同步时间戳，下次将强制执行同步。");
    }


    @Override
    public void onCreate() {
        super.onCreate();
        database = AppDatabase.getInstance(this);
        ftpHelper = new FtpHelper();
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 创建缩略图目录
        thumbnailDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "thumbnails");
        if (!thumbnailDir.exists()) {
            thumbnailDir.mkdirs();
        }

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification("正在检查同步状态..."));

        if (isRunning.get()) {
            return START_STICKY;
        }

        // 在后台线程执行同步任务
        executor.execute(this::syncAllImages);

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void syncAllImages() {
        isRunning.set(true);

        // 1. 检查是否在10天免打扰期内
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastFullSyncTime = prefs.getLong(KEY_LAST_FULL_SYNC_TIME, 0L);
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastFullSyncTime < FULL_SYNC_INTERVAL_MS) {
            long daysRemaining = (FULL_SYNC_INTERVAL_MS - (currentTime - lastFullSyncTime)) / (24 * 60 * 60 * 1000);
            String skipMsg = "上次完全同步成功在10天内，跳过本次FTP遍历。剩余免打扰天数: " + daysRemaining;
            Log.d(TAG, skipMsg);
            updateNotification(MessageFormat.format(getString(R.string.no_need_sync),String.valueOf(daysRemaining)));
            isRunning.set(false);
            return;
        }

        updateNotification(getString(R.string.connecting));

        // 2. 连接 FTP
        if (!ftpHelper.connect(this)) {
            Log.e(TAG, "FTP 连接失败");
            updateNotification(getString(R.string.failed_to_connect));
            isRunning.set(false);
            return;
        }

        // 3. 获取所有已下载的文件路径（用于去重）
        List<String> downloadedPaths = database.downloadedFileDao().getAllDownloadedPaths();

        // 4. 遍历 FTP 获取所有图片文件
        SharedPreferences appPrefs = this.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String basePath = appPrefs.getString("ftp_base_path", "/");

        List<String> allRemoteFiles = new ArrayList<>();
        updateNotification(getString(R.string.loading_file_list));
        try {
            ftpHelper.listAllFiles(basePath, allRemoteFiles);
        } catch (Exception exception) {
            updateNotification(getString(R.string.failed_to_load_list));
            isRunning.set(false);
            return;
        }

        Log.d(TAG, "FTP 上共有 " + allRemoteFiles.size() + " 个图片文件");
        // 5. 过滤出未下载的文件
        List<String> newFiles = new ArrayList<>();
        for (String remotePath : allRemoteFiles) {
            if (!downloadedPaths.contains(remotePath)) {
                newFiles.add(remotePath);
            }
        }
        Log.d(TAG, "需要下载 " + newFiles.size() + " 个新文件");
        updateNotification(MessageFormat.format(getString(R.string.x_files_to_download),newFiles.size()));

        // 6. 逐个下载
        int total = newFiles.size();
        int current = 0;
        int successCount = 0;

        // 如果没有任何新文件需要下载，说明也是“完全成功”的状态
        boolean isFullSuccess = (total == 0);

        for (String remotePath : newFiles) {
            current++;
            String fileName = remotePath.substring(remotePath.lastIndexOf("/") + 1);

            updateNotification(MessageFormat.format(getString(R.string.downloading),current,total,fileName));

            File tempFile = new File(getCacheDir(), "temp_" + fileName);

            boolean downloadSuccess = false;
            int retryCount = 0;
            final int MAX_RETRY = 3;

            while (!downloadSuccess && retryCount < MAX_RETRY) {
                if (retryCount > 0 && tempFile.exists()) {
                    tempFile.delete();
                    Log.w(TAG, "准备第 " + retryCount + " 次重试下载: " + fileName);
                    try { Thread.sleep(1000 * retryCount); } catch (InterruptedException e) {}
                }

                downloadSuccess = ftpHelper.downloadFile(remotePath, tempFile);

                if (!downloadSuccess) {
                    Log.e(TAG, "下载失败，尝试重新连接 FTP...: " + remotePath);
                    boolean reconnectSuccess = ftpHelper.reconnect(this);
                    if (!reconnectSuccess) {
                        Log.e(TAG, "FTP 重连失败，放弃下载: " + remotePath);
                        break;
                    }
                }
                retryCount++;
            }

            if (!downloadSuccess) {
                Log.e(TAG, "多次重试后依然下载失败: " + remotePath);
                if (tempFile.exists()) tempFile.delete();
                // 只要有一个文件最终下载失败，就不能算作“完全成功”
                continue;
            }

            if (tempFile.length() < 1024) {
                Log.e(TAG, "文件过小，判定为下载不完整，删除: " + tempFile.getName());
                tempFile.delete();
                continue;
            }

            BitmapFactory.Options checkOptions = new BitmapFactory.Options();
            checkOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tempFile.getAbsolutePath(), checkOptions);

            if (checkOptions.outWidth <= 0 || checkOptions.outHeight <= 0) {
                Log.e(TAG, "文件解码边界失败，判定为损坏，删除: " + tempFile.getName());
                tempFile.delete();
                continue;
            }

            String relativePath = remotePath;
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            String thumbnailRelativePath = relativePath.substring(0, relativePath.lastIndexOf('.'))
                    + "_thumb.jpg";
            File thumbnailFile = new File(thumbnailDir, thumbnailRelativePath);

            File parentDir = thumbnailFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            String thumbnailPath = ThumbnailHelper.createAndSaveThumbnail(
                    tempFile, thumbnailFile
            );

            if (thumbnailPath == null) {
                Log.e(TAG, "生成缩略图失败: " + remotePath);
                tempFile.delete();
                continue;
            }

            ThumbnailHelper.ExifInfo exifInfo = ThumbnailHelper.copyExifInfo(tempFile, thumbnailFile);

            String localUri = Uri.fromFile(new File(thumbnailPath)).toString();
            database.imageFtpDao().insert(new ImageFtpEntity(localUri, remotePath));

            database.downloadedFileDao().insert(
                    new DownloadedFileEntity(remotePath, thumbnailPath, System.currentTimeMillis(), exifInfo.captureTime == 0 ? tempFile.lastModified() : exifInfo.captureTime)
            );

            tempFile.delete();

            successCount++;
            Log.d(TAG, "成功下载并生成缩略图: " + remotePath + " -> " + thumbnailPath);
        }

        // 7. 判定本次同步是否“完全成功”
        // 条件：需要下载的文件总数 > 0 且 实际成功下载数 == 需要下载的文件总数
        if (total > 0 && successCount == total) {
            isFullSuccess = true;
        }

        // 8. 如果完全成功，记录当前时间戳
        if (isFullSuccess) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(KEY_LAST_FULL_SYNC_TIME, System.currentTimeMillis());
            editor.apply();
            Log.d(TAG, "本次同步完全成功，已记录时间戳，未来10天将跳过FTP遍历。");
        } else {
            Log.d(TAG, "本次同步存在失败或跳过的文件，不记录完全成功时间戳，下次将继续尝试。");
        }

        // 9. 断开 FTP 连接
        ftpHelper.disconnect();

        // 10. 更新通知
        String resultMsg = "同步完成，共下载 " + successCount + " 张新图片";
        updateNotification(MessageFormat.format(getString(R.string.sync_done),successCount));
        Log.d(TAG, resultMsg);

        isRunning.set(false);

        if (successCount > 0) {
            Intent intent = new Intent("com.echo2080.ppicsync.REFRESH_IMAGES");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }

    // 以下方法保持不变...
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "图片同步服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于显示图片同步进度");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PicSync 同步服务")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        mainHandler.post(() -> {
            startForeground(NOTIFICATION_ID, createNotification(content));
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        ftpHelper.disconnect();
    }
}