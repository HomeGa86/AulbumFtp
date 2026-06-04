package com.echo2080.picsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncService extends Service implements DownloadProgressListener {

    private static final String TAG = "SyncService";
    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String PREFS_NAME = "AppSettings";
    private static final String KEY_LAST_FULL_SYNC_TIME = "last_full_sync_time";
    private static final String KEY_LAST_UPLOAD_TIME = "last_upload_time";
    private static final long FULL_SYNC_INTERVAL_MS = 10 * 24 * 60 * 60 * 1000L;
    private LogHelper logHelper;

    private AppDatabase database;
    private FtpInterface ftpHelper;
    private ExecutorService executor;
    private Handler mainHandler;
    private AtomicBoolean isRunning = new AtomicBoolean(false);

    private File thumbnailDir;
    private int currentProgress;
    private String progressText;
    private int currentFileIndex;
    private String currentFileName;
    private int totalFiles;

    private final IBinder binder = new LocalBinder();
    // 使用 CopyOnWriteArrayList 保证多线程环境下的安全（适合读多写少的场景）
    private final List<OnDataUpdateListener> dataListeners = new CopyOnWriteArrayList<>();


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
        ftpHelper = new FtpHelperProxy(this);

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

        if (isRunning.get()) {
            return START_STICKY;
        }
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.check_sync_status)));

        executor.execute(() -> {
            try {
                isRunning.set(true);
                downloadAllFiles(); // ⬅️ 内部改为通用的多媒体同步

                // 下载完成后，检查是否需要上传
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                boolean enableUpload = prefs.getBoolean("enable_upload", false);
                if (enableUpload) {
                    uploadLocalFiles();
                }
            } finally {
                isRunning.set(false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                mainHandler.post(this::stopSelf);
            }
        });
        return START_STICKY;
    }


    private void downloadAllFiles() {


        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastFullSyncTime = prefs.getLong(KEY_LAST_FULL_SYNC_TIME, 0L);
        long currentTime = System.currentTimeMillis();

//        migrateCaptureTime();
//        if(2==2) {
//            return;
//        }

        if (currentTime - lastFullSyncTime < FULL_SYNC_INTERVAL_MS) {
            long daysRemaining = (FULL_SYNC_INTERVAL_MS - (currentTime - lastFullSyncTime)) / (24 * 60 * 60 * 1000);
            String skipMsg = "上次完全同步成功在10天内，跳过本次FTP遍历。剩余免打扰天数: " + daysRemaining;
            Log.d(TAG, skipMsg);
            // 确保资源存在
            String msg = MessageFormat.format(getString(R.string.no_need_sync), String.valueOf(daysRemaining));
            updateNotification(MessageFormat.format(getString(R.string.no_need_sync), String.valueOf(daysRemaining)));
            notifyUI(msg);

            return;
        }

        updateNotification(getString(R.string.connecting));
        notifyUI(getString(R.string.connecting));

        if (!ftpHelper.connect(this)) {
            Log.e(TAG, "FTP 连接失败");
            updateNotification(getString(R.string.failed_to_connect));
            notifyUI(getString(R.string.failed_to_connect));

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
        notifyUI(getString(R.string.loading_file_list));
        ServerFileDao serverFileDao = database.serverFileDao();
        List<String> cachedFilesList = serverFileDao.getAllFilePaths();
        if (cachedFilesList != null && !cachedFilesList.isEmpty()) {
            allRemoteFiles.addAll(cachedFilesList);
            logHelper.logToFile("Load file list from local database, no need to get list from server");
            Log.d(TAG, "命中缓存：从本地数据库加载了 " + allRemoteFiles.size() + " 个服务器文件路径。");
        } else {
            try {
                ftpHelper.listAllFiles(basePath, allRemoteFiles);
                List<ServerFileEntity> entities = new ArrayList<>();
                for (String path : allRemoteFiles) {
                    entities.add(new ServerFileEntity(path));
                }
                serverFileDao.insertAll(entities);
            } catch (Exception exception) {
                updateNotification(getString(R.string.failed_to_load_list));
                notifyUI(getString(R.string.failed_to_load_list));

                return;
            }
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
        notifyUI(MessageFormat.format(getString(R.string.x_files_to_download), newFiles.size()));


        int total = newFiles.size();
        int current = 0;
        int successCount = 0;
        int failedToProcessCount = 0;
        int failedToDownloadCount = 0;

        boolean isFullSuccess = (total == 0);
        this.totalFiles = newFiles.size();
        updateHandler.postDelayed(updateNotificationRunnable, UPDATE_INTERVAL);

        for (String remotePath : newFiles) {
            // 💡 检查线程是否被中断，如果是则立即退出
            if (Thread.currentThread().isInterrupted() || !isRunning.get()) {
                Log.d(TAG, "下载任务被中断，停止下载");
                logHelper.logToFile("Download task interrupted, stopping download");
                ftpHelper.disconnect();
                updateHandler.removeCallbacks(updateNotificationRunnable);
                return;
            }
            
            current++;
            String fileName = remotePath.substring(remotePath.lastIndexOf("/") + 1);
            this.currentFileIndex = current; // 从1开始计数
            this.currentFileName = fileName;
            this.currentProgress = 0; // 重置进度
            this.progressText = "";

            updateNotification(MessageFormat.format(getString(R.string.downloading), current, total, fileName));
            notifyUI(MessageFormat.format(getString(R.string.downloading), current, total, fileName));


            File tempFile = new File(getCacheDir(), "temp_" + fileName);
            if (tempFile.exists()) tempFile.delete(); // Delete the file first


            boolean downloadSuccess = false;
            int retryCount = 0;
            final int MAX_RETRY = 10;

            // 💡 新增：记录当前已经下载的字节数，初始为 0
            long downloadedBytes = 0;

            while (!downloadSuccess && retryCount < MAX_RETRY) {
                // 💡 在每次重试前检查线程是否被中断
                if (Thread.currentThread().isInterrupted() || !isRunning.get()) {
                    Log.d(TAG, "下载任务在重试时被中断");
                    logHelper.logToFile("Download task interrupted during retry");
                    ftpHelper.disconnect();
                    updateHandler.removeCallbacks(updateNotificationRunnable);
                    return;
                }
                
                if (retryCount > 0) {
                    Log.w(TAG, "准备第 " + retryCount + " 次重试下载: " + fileName + " (已从 " + downloadedBytes + " 字节处继续)");
                    try {
                        Thread.sleep(10000 * retryCount);
                    } catch (InterruptedException e) {
                        // 💡 FIX: 捕获到中断异常时，恢复中断状态并退出
                        Thread.currentThread().interrupt();
                        logHelper.logToFile("Download sleep interrupted, stopping download");
                        ftpHelper.disconnect();
                        updateHandler.removeCallbacks(updateNotificationRunnable);
                        return;
                    }

                    // 💡 FIX: 重试时重置进度并更新通知显示重试状态
                    this.currentProgress = 0;
                    this.progressText = getString(R.string.reconnecting) + retryCount + "/" + MAX_RETRY;
                    updateNotification(MessageFormat.format(
                            getString(R.string.downloading),
                            current, total, fileName + " (" + this.progressText + ")"
                    ));
                    notifyUI(MessageFormat.format(
                            getString(R.string.downloading),
                            current, total, fileName + " (" + this.progressText + ")"
                    ));

                    boolean reconnectSuccess = ftpHelper.reconnect(this);
                    if (reconnectSuccess) {
                        //After reconnected, sleep for a while to wait the connection to be stable
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            logHelper.logToFile("Failed to sleep for" + 1000);
                            logHelper.logToFile(android.util.Log.getStackTraceString(e));
                        }
                        retryCount = 0;
                        logHelper.logToFile(getString(R.string.reconnected) + ":" + remotePath);
                        updateNotification(getString(R.string.reconnected));
                        notifyUI(getString(R.string.reconnected));

                    } else {
                        retryCount++;
                        logHelper.logToFile("Failed to reconnect:" + remotePath);
                        continue;
                    }
                }

                FtpInterface realHelper = ftpHelper;
                if (ftpHelper instanceof FtpHelperProxy) {
                    realHelper = ((FtpHelperProxy) ftpHelper).getActiveHelper();
                }

                if (realHelper instanceof SftpHelper && downloadedBytes > 0) {
                    SftpHelper sftpHelper = (SftpHelper) realHelper;
                    downloadSuccess = sftpHelper.downloadFileWithResume(remotePath, tempFile, downloadedBytes, this);
                } else {
                    downloadSuccess = ftpHelper.downloadFile(remotePath, tempFile, this);
                }

                if (downloadSuccess) {
                    break; // 下载成功，跳出循环
                }

                // 💡 下载失败的处理逻辑
                if (tempFile.exists()) {
                    // 更新已下载的字节数，为下一次"断点续传"做准备
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
                thumbnailPath = ThumbnailHelper.createAndSaveThumbnailForVideo(tempFile, thumbnailFile, logHelper, this);
                if (thumbnailPath != null) {
                    parseSuccess = true;
                    captureTime = ThumbnailHelper.getVideoCaptureTime(tempFile.getAbsolutePath(), fileName);
                }

            } else {
                thumbnailPath = ThumbnailHelper.createAndSaveThumbnail(tempFile, thumbnailFile, logHelper);
                if (thumbnailPath != null) {
                    parseSuccess = true;
                    ThumbnailHelper.ExifInfo exifInfo = ThumbnailHelper.copyExifInfo(tempFile, thumbnailFile);
                    if (exifInfo != null && exifInfo.captureTime > 0) {
                        captureTime = exifInfo.captureTime;
                    }
                }
            }

            // 校验解析与缩略图是否生成成功
            if (!parseSuccess || thumbnailPath == null) {
                Log.e(TAG, "解析文件或生成缩略图失败: " + remotePath);
                logHelper.logToFile("Failed to generate thumbnail, parseSuccess is false or thumbnailPath is null:" + tempFile.getAbsolutePath());
                tempFile.delete(); // 清理残留空间
                failedToProcessCount++;
                if (fileType == FileType.VIDEO) {
                    //Ignore this file in the future, don't download it again
                    database.downloadedFileDao().insert(
                            new DownloadedFileEntity(remotePath, "", System.currentTimeMillis(), captureTime, fileType)
                    );
                }
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
            notifyUI(MessageFormat.format(getString(R.string.sync_done), successCount));
            Log.d(TAG, "本次同步完全成功，未来10天将跳过FTP遍历。");
        } else {
            Log.d(TAG, "本次同步存在失败文件，不记录完全成功时间戳。");
            updateNotification(MessageFormat.format(getString(R.string.part_done), failedToDownloadCount, failedToProcessCount));
            notifyUI(MessageFormat.format(getString(R.string.part_done), failedToDownloadCount, failedToProcessCount));
        }


        ftpHelper.disconnect();
        updateHandler.removeCallbacks(updateNotificationRunnable);


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
                    getString(R.string.download_notification_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.download_notification_name));
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
                .setContentTitle(getString(R.string.notification_title))
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

    // 用于节流通知更新的 Handler
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private static final long UPDATE_INTERVAL = 10000; // 10秒

    // 3. Runnable 用于更新通知栏
    private final Runnable updateNotificationRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning.get()) {
                String content = MessageFormat.format(
                        "{0}/{1}|{2}|{3}",
                        currentFileIndex, totalFiles, progressText, currentFileName
                );
                updateNotification(content);
                // 重复发送自己，保持 10 秒一次的节奏
                updateHandler.postDelayed(this, UPDATE_INTERVAL);
            }
        }
    };

    @Override
    public void onProgress(int progress, String progressText) {
        this.currentProgress = progress;
        this.progressText = progressText;
        // 注意：这里不直接更新通知，而是依赖定时器
    }

    @Override
    public void onFinish(boolean success) {
        // 下载完成后的处理
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called"); 
        
        isRunning.set(false);
        
        // 💡 中断正在执行的任务
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            Log.d(TAG, "Executor shutdown initiated");
        }
        
        // 💡 移除所有通知更新回调
        updateHandler.removeCallbacks(updateNotificationRunnable);
        
        // 💡 断开 FTP 连接
        if (ftpHelper != null) {
            ftpHelper.disconnect();
        }
        
        // 💡 确保移除前台服务和通知
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
            Log.d(TAG, "stopForeground called with REMOVE flag");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping foreground", e);
        }
        
        // 💡 显式取消通知（作为双重保险）
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
            Log.d(TAG, "Notification explicitly cancelled");
        }
    }

    public interface OnDataUpdateListener {
        void onDataUpdated(String data);
    }

    public class LocalBinder extends Binder {
        public SyncService getService() {
            return SyncService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // 注册监听：添加到列表中
    public void registerDataListener(OnDataUpdateListener listener) {
        if (listener != null && !dataListeners.contains(listener)) {
            dataListeners.add(listener);
        }
    }

    // 注销监听：从列表中移除
    public void unregisterDataListener(OnDataUpdateListener listener) {
        dataListeners.remove(listener);
    }

    public void notifyUI(String msg) {
        for (OnDataUpdateListener listener : dataListeners) {
            if (listener != null) {
                listener.onDataUpdated(msg);
            }
        }
    }

    public void migrateCaptureTime() {
        // 1. 设定时间阈值：2026年4月30日 23:59:59 的时间戳
        long thresholdTimestamp = 1777564799000L;

        // 2. 从数据库取出所有 captureTime 在 2026年4月份之后的记录
        List<DownloadedFileEntity> files = database.downloadedFileDao().getFilesAfterTimestamp(thresholdTimestamp);

        if (files == null || files.isEmpty()) {
            System.out.println("没有找到需要更新的记录。");
            return;
        }

        int updateCount = 0;
        // 3. 遍历每一条记录
        for (DownloadedFileEntity file : files) {
            // 获取 ftpPath（例如：/folder1/folder2/img_2016-05-12_abc.jpeg）
            String ftpPath = file.getFtpPath();

            // 从文件名中提取拍摄时间
            long extractedTime = ThumbnailHelper.extractTimestampFromFilename(ftpPath);

            logHelper.logToFile(ftpPath);


            // 4. 如果成功提取到了有效的时间（不为0），则更新实体并保存回数据库
            if (extractedTime > 0) {
                file.setCaptureTime(extractedTime);
                database.downloadedFileDao().updateCaptureTime(file);
                updateCount++;
                System.out.println("更新成功: " + ftpPath + " -> 新时间: " + extractedTime);
            } else {
                System.out.println("提取失败: " + ftpPath);
            }
        }

        System.out.println("数据清洗完成，共更新了 " + updateCount + " 条记录。");
        notifyUI("数据清洗完成，共更新了 " + updateCount + " 条记录。");
    }

    /**
     * 上传本地相册文件到服务器
     */
    private void uploadLocalFiles() {

        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        updateNotification(getString(R.string.connecting));
        notifyUI(getString(R.string.connecting));

        if (!ftpHelper.connect(this)) {
            Log.e(TAG, "FTP 连接失败");
            updateNotification(getString(R.string.failed_to_connect));
            notifyUI(getString(R.string.failed_to_connect));

            return;
        }

        // 💡 从数据库加载已上传的文件名列表（仅文件名，不含路径）
        List<String> uploadedFileNames = database.uploadedFileDao().getAllUploadedFileNames();
        Log.d(TAG, "已上传文件数量: " + uploadedFileNames.size());
        logHelper.logToFile("Loaded " + uploadedFileNames.size() + " uploaded file names from database");

        // 获取当月目录名，例如 202606_phoneID
        SimpleDateFormat monthFormat = new SimpleDateFormat("yyyyMM", Locale.US);
        String monthDir = monthFormat.format(new Date());
        String deviceId = getDeviceId();

        SharedPreferences appPrefs = this.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String basePath = appPrefs.getString("ftp_base_path", "/");
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        String uploadDir = basePath + "upload/" + monthDir + "_" + deviceId + "/";

        // 扫描本地相册文件
        List<File> localFiles = scanLocalMediaFiles();
        if (localFiles.isEmpty()) {
            updateNotification(getString(R.string.no_need_upload));
            notifyUI(getString(R.string.no_need_upload));
            ftpHelper.disconnect();

            return;
        }

        Log.d(TAG, "找到 " + localFiles.size() + " 个本地文件待上传");
        logHelper.logToFile("Found " + localFiles.size() + " local files to upload");

        updateNotification(MessageFormat.format(getString(R.string.x_files_to_upload), localFiles.size()));
        notifyUI(MessageFormat.format(getString(R.string.x_files_to_upload), localFiles.size()));

        int total = localFiles.size();
        int successCount = 0;
        int failedCount = 0;
        int skippedCount = 0; // 💡 新增：记录跳过的文件数

        this.totalFiles = total;
        updateHandler.postDelayed(updateNotificationRunnable, UPDATE_INTERVAL);

        for (int i = 0; i < localFiles.size(); i++) {
            // 💡 检查线程是否被中断，如果是则立即退出
            if (Thread.currentThread().isInterrupted() || !isRunning.get()) {
                Log.d(TAG, "上传任务被中断，停止上传");
                logHelper.logToFile("Upload task interrupted, stopping upload");
                ftpHelper.disconnect();
                updateHandler.removeCallbacks(updateNotificationRunnable);
                return;
            }
            
            File localFile = localFiles.get(i);
            String fileName = localFile.getName(); // 💡 只获取文件名
            
            this.currentFileIndex = i + 1;
            this.currentFileName = fileName;
            this.currentProgress = 0;
            this.progressText = "";

            updateNotification(MessageFormat.format(getString(R.string.uploading), currentFileIndex, total, currentFileName));
            notifyUI(MessageFormat.format(getString(R.string.uploading), currentFileIndex, total, currentFileName));

            // 💡 检查该文件名是否已经上传过（基于文件名比较）
            if (uploadedFileNames.contains(fileName)) {
                Log.d(TAG, "文件已上传过，跳过: " + fileName);
                logHelper.logToFile("File already uploaded, skip: " + fileName);
                skippedCount++;
                successCount++;
                continue;
            }

            // 💡 检查服务器上是否已存在同名文件，并比较大小
            String remoteFilePath = uploadDir + fileName;
            long remoteFileSize = ftpHelper.getFileSize(remoteFilePath);
            long localFileSize = localFile.length();

            if (remoteFileSize >= 0) {
                // 文件已存在，比较大小
                if (remoteFileSize == localFileSize) {
                    // 大小相同，判定为完整上传过，跳过
                    Log.d(TAG, "服务器文件与本地大小一致，跳过上传: " + fileName + " (大小: " + remoteFileSize + " bytes)");
                    logHelper.logToFile("Skip upload, file size matches on server: " + fileName + " (size: " + remoteFileSize + " bytes)");
                    
                    // 💡 记录到数据库，避免下次重复检查
                    database.uploadedFileDao().insert(new UploadedFileEntity(fileName, System.currentTimeMillis()));
                    uploadedFileNames.add(fileName);
                    
                    successCount++;
                    continue;
                } else {
                    // 大小不同，说明上次可能上传中断或文件被修改，需要重新上传
                    Log.w(TAG, "服务器文件与本地大小不一致，将重新上传: " + fileName + " (远程: " + remoteFileSize + " bytes, 本地: " + localFileSize + " bytes)");
                    logHelper.logToFile("File size mismatch, will re-upload: " + fileName + " (remote: " + remoteFileSize + ", local: " + localFileSize + ")");
                }
            }

            boolean uploadSuccess = false;
            int retryCount = 0;
            final int MAX_RETRY = 10;

            // 💡 新增：记录当前已经上传的字节数，初始为 0
            long uploadedBytes = 0;

            while (!uploadSuccess && retryCount < MAX_RETRY) {
                // 💡 在每次重试前检查线程是否被中断
                if (Thread.currentThread().isInterrupted() || !isRunning.get()) {
                    Log.d(TAG, "上传任务在重试时被中断");
                    logHelper.logToFile("Upload task interrupted during retry");
                    ftpHelper.disconnect();
                    updateHandler.removeCallbacks(updateNotificationRunnable);
                    return;
                }
                
                if (retryCount > 0) {
                    Log.w(TAG, "准备第 " + retryCount + " 次重试上传: " + fileName + " (已从 " + uploadedBytes + " 字节处继续)");
                    try {
                        Thread.sleep(10000 * retryCount);
                    } catch (InterruptedException e) {
                        // 💡 FIX: 捕获到中断异常时，恢复中断状态并退出
                        Thread.currentThread().interrupt();
                        logHelper.logToFile("Upload sleep interrupted, stopping upload");
                        ftpHelper.disconnect();
                        updateHandler.removeCallbacks(updateNotificationRunnable);
                        return;
                    }

                    // 💡 FIX: 重试时重置进度并更新通知显示重试状态
                    this.currentProgress = 0;
                    this.progressText = getString(R.string.reconnecting) + retryCount + "/" + MAX_RETRY;
                    updateNotification(MessageFormat.format(
                            getString(R.string.uploading),
                            currentFileIndex, total, currentFileName + " (" + this.progressText + ")"
                    ));
                    notifyUI(MessageFormat.format(
                            getString(R.string.uploading),
                            currentFileIndex, total, currentFileName + " (" + this.progressText + ")"
                    ));

                    boolean reconnectSuccess = ftpHelper.reconnect(this);
                    if (reconnectSuccess) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            logHelper.logToFile("Failed to sleep for 1000");
                            logHelper.logToFile(android.util.Log.getStackTraceString(e));
                        }
                        retryCount = 0;
                        logHelper.logToFile(getString(R.string.reconnected) + ":" + remoteFilePath);
                        updateNotification(getString(R.string.reconnected));
                        notifyUI(getString(R.string.reconnected));
                    } else {
                        retryCount++;
                        logHelper.logToFile("Failed to reconnect:" + remoteFilePath);
                        continue;
                    }
                }

                FtpInterface realHelper = ftpHelper;
                if (ftpHelper instanceof FtpHelperProxy) {
                    realHelper = ((FtpHelperProxy) ftpHelper).getActiveHelper();
                }

                // 💡 使用带进度监听的上传方法，SFTP 支持断点续传
                if (realHelper instanceof SftpHelper) {
                    SftpHelper sftpHelper = (SftpHelper) realHelper;
                    uploadSuccess = sftpHelper.uploadFile(remoteFilePath, localFile, this);
                } else {
                    uploadSuccess = ftpHelper.uploadFile(remoteFilePath, localFile, this);
                }

                if (uploadSuccess) {
                    break; // 上传成功，跳出循环
                }

                // 💡 上传失败的处理逻辑 - 检查已上传的字节数用于断点续传
                try {
                    long newRemoteFileSize = ftpHelper.getFileSize(remoteFilePath);
                    if (newRemoteFileSize > 0) {
                        uploadedBytes = newRemoteFileSize;
                        Log.d(TAG, "上传中断，远程文件大小: " + uploadedBytes + " bytes");
                    } else {
                        uploadedBytes = 0;
                    }
                } catch (Exception e) {
                    uploadedBytes = 0;
                }

                retryCount++;
            }

            if (uploadSuccess) {
                successCount++;
                Log.d(TAG, "成功上传: " + fileName + " -> " + remoteFilePath);
                logHelper.logToFile("Successfully uploaded: " + localFile.getAbsolutePath() + " -> " + remoteFilePath);
                
                // 💡 上传成功后，仅记录文件名到数据库
                database.uploadedFileDao().insert(new UploadedFileEntity(fileName, System.currentTimeMillis()));
                uploadedFileNames.add(fileName);
            } else {
                failedCount++;
                Log.e(TAG, "上传失败: " + fileName);
                logHelper.logToFile("Failed to upload: " + localFile.getAbsolutePath());
            }
        }

        ftpHelper.disconnect();
        updateHandler.removeCallbacks(updateNotificationRunnable);


        if (failedCount == 0) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(KEY_LAST_UPLOAD_TIME, System.currentTimeMillis());
            editor.apply();
            String msg = MessageFormat.format(getString(R.string.upload_complete), successCount) 
                    + (skippedCount > 0 ? " (" + skippedCount + " skipped)" : "");
            updateNotification(msg);
            notifyUI(msg);
        } else {
            String msg = MessageFormat.format(getString(R.string.upload_part_done), successCount, failedCount)
                    + (skippedCount > 0 ? " (" + skippedCount + " skipped)" : "");
            updateNotification(msg);
            notifyUI(msg);
        }

        Log.d(TAG, "上传完成: 成功 " + successCount + ", 失败 " + failedCount + ", 跳过 " + skippedCount);
    }

    /**
     * 扫描本地相册中的图片和视频文件
     */
    private List<File> scanLocalMediaFiles() {
        List<File> mediaFiles = new ArrayList<>();

        // 使用 MediaStore 扫描图片
        Uri imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] imageProjection = new String[]{
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME
        };

        try (Cursor imageCursor = getContentResolver().query(
                imagesUri, imageProjection, null, null, null)) {
            if (imageCursor != null) {
                int dataColumnIndex = imageCursor.getColumnIndex(MediaStore.Images.Media.DATA);
                int count = 0;
                while (imageCursor.moveToNext()) {
                    String filePath = imageCursor.getString(dataColumnIndex);
                    if (filePath != null) {
                        File file = new File(filePath);
                        if (file.exists()) {
                            mediaFiles.add(file);
                            count++;
                        }
                    }
                }
                Log.d(TAG, "扫描到 " + count + " 个图片文件");
                logHelper.logToFile("Scanned " + count + " image files from MediaStore");
            } else {
                Log.w(TAG, "图片查询返回 null，可能没有权限");
                logHelper.logToFile("Image query returned null, possibly no permission");
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描图片失败", e);
            logHelper.logToFile("Failed to scan images: " + e.getMessage());
        }

        // 使用 MediaStore 扫描视频
        Uri videosUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] videoProjection = new String[]{
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME
        };

        try (Cursor videoCursor = getContentResolver().query(
                videosUri, videoProjection, null, null, null)) {
            if (videoCursor != null) {
                int dataColumnIndex = videoCursor.getColumnIndex(MediaStore.Video.Media.DATA);
                int count = 0;
                while (videoCursor.moveToNext()) {
                    String filePath = videoCursor.getString(dataColumnIndex);
                    if (filePath != null) {
                        File file = new File(filePath);
                        if (file.exists()) {
                            mediaFiles.add(file);
                            count++;
                        }
                    }
                }
                Log.d(TAG, "扫描到 " + count + " 个视频文件");
                logHelper.logToFile("Scanned " + count + " video files from MediaStore");
            } else {
                Log.w(TAG, "视频查询返回 null，可能没有权限");
                logHelper.logToFile("Video query returned null, possibly no permission");
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描视频失败", e);
            logHelper.logToFile("Failed to scan videos: " + e.getMessage());
        }

        Log.d(TAG, "总共找到 " + mediaFiles.size() + " 个本地媒体文件");
        logHelper.logToFile("Total local media files found: " + mediaFiles.size());
        return mediaFiles;
    }


    /**
     * 获取设备唯一标识符（用于多设备上传时区分文件夹）
     *
     * @return 设备ID的后8位字符（保护隐私同时保证唯一性）
     */
    private String getDeviceId() {
        try {
            String androidId = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID
            );

            if (androidId != null && !androidId.isEmpty()) {
                // 取后8位作为设备标识，既保证唯一性又保护隐私
                return androidId.length() > 8
                        ? androidId.substring(androidId.length() - 8).toUpperCase()
                        : androidId.toUpperCase();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取设备ID失败", e);
            logHelper.logToFile("Failed to get device ID: " + e.getMessage());
        }

        // 如果获取失败，使用时间戳的最后8位作为备用方案
        return String.valueOf(System.currentTimeMillis()).substring(5).toUpperCase();
    }
}




