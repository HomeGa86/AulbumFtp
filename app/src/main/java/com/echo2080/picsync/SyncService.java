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

    private AppDatabase database;
    private FtpHelper ftpHelper;
    private ExecutorService executor;
    private Handler mainHandler;
    private AtomicBoolean isRunning = new AtomicBoolean(false);

    // 缩略图保存目录
    private File thumbnailDir;

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

        // 创建通知渠道（Android 8.0+ 需要）
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("正在同步图片..."));

        // 如果已经在运行，不再重复启动
        if (isRunning.get()) {
            return START_STICKY;
        }

        // 在后台线程执行同步任务
        executor.execute(this::syncAllImages);

        return START_STICKY; // 服务被杀死后自动重启
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 核心同步方法：遍历 FTP、下载、生成缩略图、记录路径、删除原图
     */
    /**
     * 核心同步方法：遍历 FTP、下载、生成缩略图、记录路径、删除原图
     */
    private void syncAllImages() {
        isRunning.set(true);
        updateNotification("正在连接 FTP 服务器...");

        // 1. 连接 FTP
        if (!ftpHelper.connect(this)) {
            Log.e(TAG, "FTP 连接失败");
            updateNotification("FTP 连接失败，稍后重试");
            isRunning.set(false);
            return;
        }

        // 2. 获取所有已下载的文件路径（用于去重）
        List<String> downloadedPaths = database.downloadedFileDao().getAllDownloadedPaths();

        // 3. 遍历 FTP 获取所有图片文件
        SharedPreferences prefs = this.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String basePath = prefs.getString("ftp_base_path", "/");

        List<String> allRemoteFiles = new ArrayList<>();
        updateNotification("正在查找远程图片列表...");
        ftpHelper.listAllFiles(basePath, allRemoteFiles);
        Log.d(TAG, "FTP 上共有 " + allRemoteFiles.size() + " 个图片文件");
        updateNotification("FTP 上共有 " + allRemoteFiles.size() + " 个图片文件");

        // 4. 过滤出未下载的文件
        List<String> newFiles = new ArrayList<>();
        for (String remotePath : allRemoteFiles) {
            if (!downloadedPaths.contains(remotePath)) {
                newFiles.add(remotePath);
            }
        }
        Log.d(TAG, "需要下载 " + newFiles.size() + " 个新文件");
        updateNotification("需要下载 " + newFiles.size() + " 个新文件");

        // 5. 逐个下载
        int total = newFiles.size();
        int current = 0;
        int successCount = 0;

        for (String remotePath : newFiles) {
            current++;
            String fileName = remotePath.substring(remotePath.lastIndexOf("/") + 1);

            updateNotification("正在下载 (" + current + "/" + total + "): " + fileName);

            // 5.1 下载原图到临时文件 (加入重试机制)
            File tempFile = new File(getCacheDir(), "temp_" + fileName);

            boolean downloadSuccess = false;
            int retryCount = 0;
            final int MAX_RETRY = 3; // 最多重试3次

            while (!downloadSuccess && retryCount < MAX_RETRY) {
                // 如果是重试，先删除上次可能下载失败的残缺文件
                if (retryCount > 0 && tempFile.exists()) {
                    tempFile.delete();
                    Log.w(TAG, "准备第 " + retryCount + " 次重试下载: " + fileName);
                    // 重试前稍微等待一下，给网络一点恢复时间
                    try { Thread.sleep(1000 * retryCount); } catch (InterruptedException e) {}
                }

                downloadSuccess = ftpHelper.downloadFile(remotePath, tempFile);

                // 如果下载失败，尝试重新连接 FTP
                if (!downloadSuccess) {
                    Log.e(TAG, "下载失败，尝试重新连接 FTP...: " + remotePath);
                    boolean reconnectSuccess = ftpHelper.reconnect(this);
                    if (!reconnectSuccess) {
                        Log.e(TAG, "FTP 重连失败，放弃下载: " + remotePath);
                        break; // 重连也失败了，彻底放弃这张图
                    }
                }
                retryCount++;
            }

            // 如果最终还是没下载成功，跳过这张图
            if (!downloadSuccess) {
                Log.e(TAG, "多次重试后依然下载失败: " + remotePath);
                if (tempFile.exists()) tempFile.delete();
                continue;
            }

            // 🔥 强制校验文件完整性
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


            // ⬅️ 5.2 构建缩略图保存路径
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

            // 5.3 生成缩略图
            String thumbnailPath = ThumbnailHelper.createAndSaveThumbnail(
                    tempFile, thumbnailFile
            );

            if (thumbnailPath == null) {
                Log.e(TAG, "生成缩略图失败: " + remotePath);
                tempFile.delete();
                continue;
            }

            ThumbnailHelper.ExifInfo exifInfo = ThumbnailHelper.copyExifInfo(tempFile, thumbnailFile);

            // 5.5 保存 FTP 路径到数据库
            String localUri = Uri.fromFile(new File(thumbnailPath)).toString();
            database.imageFtpDao().insert(new ImageFtpEntity(localUri, remotePath));

            // 5.6 记录已下载的文件
            database.downloadedFileDao().insert(
                    new DownloadedFileEntity(remotePath, thumbnailPath, System.currentTimeMillis(),exifInfo.captureTime == 0 ? tempFile.lastModified() : exifInfo.captureTime)
            );

            // 5.7 删除原图
            tempFile.delete();

            successCount++;
            Log.d(TAG, "成功下载并生成缩略图: " + remotePath + " -> " + thumbnailPath);
        }

        // 6. 断开 FTP 连接
        ftpHelper.disconnect();

        // 7. 更新通知
        String resultMsg = "同步完成，共下载 " + successCount + " 张新图片";
        updateNotification(resultMsg);
        Log.d(TAG, resultMsg);

        isRunning.set(false);

        // 如果下载了图片，通知 MainActivity 刷新
        if (successCount > 0) {
            Intent intent = new Intent("com.echo2080.picsync.REFRESH_IMAGES");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }

    /**
     * 创建通知渠道
     */
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

    /**
     * 创建通知
     */
    private Notification createNotification(String content) {
        // 点击通知打开 MainActivity
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

    /**
     * 更新通知内容（需要在主线程执行）
     */
    private void updateNotification(String content) {
        mainHandler.post(() -> {
            // 修改点：直接调用 startForeground 来更新前台服务的通知，确保系统感知到服务的活跃状态
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