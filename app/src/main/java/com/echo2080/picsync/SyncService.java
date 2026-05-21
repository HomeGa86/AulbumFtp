package com.echo2080.picsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.jcraft.jsch.SftpException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncService extends Service {

    private static final String TAG = "SyncService";
    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String PREFS_NAME = "AppSettings";
    private static final String KEY_LAST_FULL_SYNC_TIME = "last_full_sync_time";
    private static final long FULL_SYNC_INTERVAL_MS = 10 * 24 * 60 * 60 * 1000L;
    private LogHelper logHelper;

    private AppDatabase database;
    private FtpInterface ftpHelper;
    private ExecutorService executor;
    private Handler mainHandler;
    private AtomicBoolean isRunning = new AtomicBoolean(false);

    private File thumbnailDir;

    public static void resetFullSyncTimestamp(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(KEY_LAST_FULL_SYNC_TIME, 0L);
        editor.apply();
        Log.d(TAG, "FTP配置已变更，已重置完全同步时间戳，下次将强制执行同步。");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logHelper = new LogHelper(this);
        database = AppDatabase.getInstance(this);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Boolean isSftp = prefs.getBoolean("is_sftp", false);
        if(isSftp)
        {
            ftpHelper = new SftpHelper(this);
        }
        else
        {
            ftpHelper = new FtpHelper(this);
        }

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

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

        executor.execute(() -> {
            try {
                syncAllFiles(); // ⬅️ 内部改为通用的多媒体同步
            } finally {
                mainHandler.post(this::stopSelf);
            }
        });
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void syncAllFiles() {
        isRunning.set(true);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastFullSyncTime = prefs.getLong(KEY_LAST_FULL_SYNC_TIME, 0L);
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastFullSyncTime < FULL_SYNC_INTERVAL_MS) {
            long daysRemaining = (FULL_SYNC_INTERVAL_MS - (currentTime - lastFullSyncTime)) / (24 * 60 * 60 * 1000);
            String skipMsg = "上次完全同步成功在10天内，跳过本次FTP遍历。剩余免打扰天数: " + daysRemaining;
            Log.d(TAG, skipMsg);
            // 确保资源存在
            updateNotification(MessageFormat.format(getString(R.string.no_need_sync), String.valueOf(daysRemaining)));
            isRunning.set(false);
            return;
        }

        updateNotification(getString(R.string.connecting));

        if (!ftpHelper.connect(this)) {
            Log.e(TAG, "FTP 连接失败");
            updateNotification(getString(R.string.failed_to_connect));
            isRunning.set(false);
            return;
        }

        List<String> downloadedPaths = database.downloadedFileDao().getAllDownloadedPaths();
        logHelper.logToFile("downloadedPaths:" + String.valueOf(downloadedPaths == null ? 0 : downloadedPaths.size()));

        SharedPreferences appPrefs = this.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String basePath = appPrefs.getString("ftp_base_path", "/");

        if (!basePath.isEmpty() && !basePath.endsWith("/")) {
            basePath += "/";
        }

        List<String> allRemoteFiles = new ArrayList<>();
        updateNotification(getString(R.string.loading_file_list));
        try {
            ftpHelper.listAllFiles(basePath, allRemoteFiles);
        } catch (Exception exception) {
            updateNotification(getString(R.string.failed_to_load_list));
            isRunning.set(false);
            return;
        }

        Log.d(TAG, "FTP 上共有 " + allRemoteFiles.size() + " 个文件");
        logHelper.logToFile("Total files on server:" + allRemoteFiles.size());


        List<String> newFiles = new ArrayList<>();
        for (String remotePath : allRemoteFiles) {
            if (!downloadedPaths.contains(remotePath)) {
                newFiles.add(remotePath);
            }
        }
        Log.d(TAG, "需要下载 " + newFiles.size() + " 个新文件");
        updateNotification(MessageFormat.format(getString(R.string.x_files_to_download), newFiles.size()));

        int total = newFiles.size();
        int current = 0;
        int successCount = 0;
        int failedToProcessCount = 0;
        int failedToDownloadCount = 0;

        boolean isFullSuccess = (total == 0);

        for (String remotePath : newFiles) {
            current++;
            String fileName = remotePath.substring(remotePath.lastIndexOf("/") + 1);

            updateNotification(MessageFormat.format(getString(R.string.downloading), current, total, fileName));

            File tempFile = new File(getCacheDir(), "temp_" + fileName);

            boolean downloadSuccess = false;
            int retryCount = 0;
            final int MAX_RETRY = 10;

            // 💡 新增：记录当前已经下载的字节数，初始为 0
            long downloadedBytes = 0;

            while (!downloadSuccess && retryCount < MAX_RETRY) {
                if (retryCount > 0) {
                    Log.w(TAG, "准备第 " + retryCount + " 次重试下载: " + fileName + " (已从 " + downloadedBytes + " 字节处继续)");
                    try { Thread.sleep(1000 * retryCount); } catch (InterruptedException e) {}

                    // 💡 关键改动：重连服务器
                    boolean reconnectSuccess = ftpHelper.reconnect(this);
                    if (!reconnectSuccess) {
                        Log.e(TAG, "FTP/SFTP 重连失败，放弃下载: " + remotePath);
                        logHelper.logToFile("reconnect failed for file:" + remotePath);
                        break;
                    }
                }

                // 💡 智能判断：如果是 SftpHelper 且已经有部分下载数据，则调用断点续传方法
                if (ftpHelper instanceof SftpHelper && downloadedBytes > 0) {
                    SftpHelper sftpHelper = (SftpHelper) ftpHelper;
                    // 传入当前的 downloadedBytes 作为起始偏移量
                    downloadSuccess = sftpHelper.downloadFileWithResume(remotePath, tempFile, downloadedBytes, null);
                } else {
                    // 普通 FtpHelper 或者第一次下载，走原有逻辑
                    // 注意：你的 FtpInterface 里的 downloadFile 最好也支持传入 listener，这里暂用 null
                    downloadSuccess = ftpHelper.downloadFile(remotePath, tempFile, null);
                }

                if (downloadSuccess) {
                    break; // 下载成功，跳出循环
                }

                // 💡 下载失败的处理逻辑
                if (tempFile.exists()) {
                    // 更新已下载的字节数，为下一次“断点续传”做准备
                    downloadedBytes = tempFile.length();
                    Log.d(TAG, "下载中断，本地临时文件大小: " + downloadedBytes + " bytes");
                } else {
                    downloadedBytes = 0;
                }

                retryCount++;
            }


            if (!downloadSuccess) {
                Log.e(TAG, "多次重试后依然下载失败: " + remotePath);
                if (tempFile.exists()) tempFile.delete();
                logHelper.logToFile("Failed to download a file after retry:" + remotePath);
                continue;
            }

            if (tempFile.length() < 2) {
                Log.e(TAG, "文件过小，判定为下载不完整，删除: " + tempFile.getName());
                logHelper.logToFile("File is too small:" + tempFile.getAbsolutePath());
                tempFile.delete();
                failedToDownloadCount++;
                continue;
            }

            // ⬅️ 新增：根据后缀名判断文件类型
            FileType fileType = getFileTypeByExtension(fileName);
            long captureTime = tempFile.lastModified();
            boolean parseSuccess = false;

            String relativePath = remotePath.startsWith("/") ? remotePath.substring(1) : remotePath;
            String thumbnailRelativePath = relativePath.substring(0, relativePath.lastIndexOf('.')) + "_thumb.jpg";
            File thumbnailFile = new File(thumbnailDir, thumbnailRelativePath);
            File parentDir = thumbnailFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            String thumbnailPath = null;

            if (fileType == FileType.VIDEO) {
                // 🎬 视频流处理分支
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(tempFile.getAbsolutePath());
                    String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

                    if (width != null && height != null && Integer.parseInt(width) > 0 && Integer.parseInt(height) > 0) {
                        parseSuccess = true;

                        // ⬅️【关键改动】：调用我们写的方法，精准获取视频的拍摄时间
                        captureTime = ThumbnailHelper.getVideoCaptureTime(tempFile.getAbsolutePath(), fileName);

                        // 提取第一帧作为微缩图
                        Bitmap videoFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (videoFrame != null) {
                            // 将视频首帧压缩并保存到本地缩略图目录
                            try (FileOutputStream fos = new FileOutputStream(thumbnailFile)) {
                                videoFrame.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                                thumbnailPath = thumbnailFile.getAbsolutePath();
                            }
                            catch (Exception ex)
                            {
                                logHelper.logToFile("Failed to generate thumbnail, videoFrame.compress failed:" + tempFile.getAbsolutePath());
                                logHelper.logToFile(android.util.Log.getStackTraceString(ex));
                            }
                            videoFrame.recycle();
                        }
                        else
                        {
                            logHelper.logToFile("Failed to generate thumbnail, videoFrame is null:" + tempFile.getAbsolutePath());
                        }
                    }
                    else
                    {
                        logHelper.logToFile("Failed to generate thumbnail or capture time, width or height is not right:" + tempFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析视频或提取首帧失败: " + fileName, e);
                    logHelper.logToFile("Failed to generate thumbnail or capture time:" + tempFile.getAbsolutePath());
                    logHelper.logToFile(android.util.Log.getStackTraceString(e));
                } finally {
                    try { retriever.release(); } catch (IOException ignored) {
                        logHelper.logToFile("Failed to release retriever:" + tempFile.getAbsolutePath());
                    }
                }
            } else {
                // 🖼️ 图片流处理分支
                BitmapFactory.Options checkOptions = new BitmapFactory.Options();
                checkOptions.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(tempFile.getAbsolutePath(), checkOptions);

                if (checkOptions.outWidth > 0 && checkOptions.outHeight > 0) {
                    parseSuccess = true;
                    thumbnailPath = ThumbnailHelper.createAndSaveThumbnail(tempFile, thumbnailFile, logHelper);
                    if (thumbnailPath != null) {
                        ThumbnailHelper.ExifInfo exifInfo = ThumbnailHelper.copyExifInfo(tempFile, thumbnailFile);
                        if (exifInfo != null && exifInfo.captureTime > 0) {
                            captureTime = exifInfo.captureTime;
                        }
                    }
                    else
                    {
                        logHelper.logToFile("Failed to generate thumbnail or capture time, thumbnailPath is null:" + tempFile.getAbsolutePath());
                    }
                }
                else
                {
                    logHelper.logToFile("Failed to generate thumbnail, width or height is not right:" + tempFile.getAbsolutePath());
                }
            }

            // 校验解析与缩略图是否生成成功
            if (!parseSuccess || thumbnailPath == null) {
                Log.e(TAG, "解析文件或生成缩略图失败: " + remotePath);
                logHelper.logToFile("Failed to generate thumbnail, parseSuccess is false or thumbnailPath is null:" + tempFile.getAbsolutePath());
                tempFile.delete(); // 清理残留空间
                failedToProcessCount++;
                continue;
            }

            // 💾 成功后持久化数据到本地 Room
            String localUri = Uri.fromFile(new File(thumbnailPath)).toString();
            database.imageFtpDao().insert(new ImageFtpEntity(localUri, remotePath));

            database.downloadedFileDao().insert(
                    new DownloadedFileEntity(remotePath, thumbnailPath, System.currentTimeMillis(), captureTime, fileType)
            );

            // ✂️ 无论是图片还是大视频，本地临时原始文件一律删除以节省存储空间
            tempFile.delete();

            successCount++;
            Log.d(TAG, "成功同步并生成缩略图[" + fileType + "]: " + remotePath + " -> " + thumbnailPath);
        }

        if (total > 0 && successCount == total) {
            isFullSuccess = true;
        }

        if (isFullSuccess) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(KEY_LAST_FULL_SYNC_TIME, System.currentTimeMillis());
            editor.apply();
            updateNotification(MessageFormat.format(getString(R.string.sync_done), successCount));
            Log.d(TAG, "本次同步完全成功，未来10天将跳过FTP遍历。");
        } else {
            Log.d(TAG, "本次同步存在失败文件，不记录完全成功时间戳。");
            updateNotification(MessageFormat.format(getString(R.string.part_done), failedToDownloadCount, failedToProcessCount));
        }


        ftpHelper.disconnect();
        isRunning.set(false);

        if (successCount > 0) {
            Intent intent = new Intent("com.echo2080.picsync.REFRESH_IMAGES");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }



    // 辅助工具方法：根据后缀名映射到文件类型枚举
    private FileType getFileTypeByExtension(String fileName) {
        if (fileName == null) return FileType.OTHER;
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".mov") || lowerName.endsWith(".avi") || lowerName.endsWith(".3gp")) {
            return FileType.VIDEO;
        } else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif")) {
            return FileType.PICTURE;
        }
        return FileType.OTHER;
    }

    // 提取的日志记录辅助方法
    private void logFailure(File logFile, String remotePath, String reason) {
        try (FileWriter fw = new FileWriter(logFile, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write("FTP路径: " + remotePath + " | 失败原因: " + reason);
            bw.newLine();
        } catch (IOException e) {
            Log.e(TAG, "写入失败日志出错", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "多媒体同步服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于显示媒体数据同步进度");
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
    }
}