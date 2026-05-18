package com.echo2080.picsync;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.ProgressBar; // 记得导入 ProgressBar


public class FullScreenActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private FullScreenPagerAdapter adapter;
    private int currentPosition;

    private File cacheDir;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private String host;
    private int port;
    private String user;
    private String password;
    private ProgressBar progressBar; // 新增：声明进度条控件
    private boolean downloadSuccess;

    public interface DownloadProgressListener {
        void onProgress(String progressText); // 用于更新进度文字（如：50% - 2.5 MB/s）
        void onFinish(boolean success);       // 用于下载结束时隐藏进度条
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen);

        // 💡 初始化你的进度条控件（假设你在 activity_full_screen.xml 里加了一个 id 为 download_progress_bar 的 ProgressBar）
        progressBar = findViewById(R.id.download_progress_bar);
        progressBar.setVisibility(View.GONE); // 默认隐藏


        Intent intent = getIntent();
        currentPosition = intent.getIntExtra("current_position", 0);

        cacheDir = new File(getCacheDir(), "full_images");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        viewPager = findViewById(R.id.view_pager);
        adapter = new FullScreenPagerAdapter(this);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(currentPosition, false);

        SharedPreferences prefs = this.getSharedPreferences("AppSettings", MODE_PRIVATE);
        host = prefs.getString("ftp_host", "");
        port = Integer.parseInt(prefs.getString("ftp_port", "21"));
        user = prefs.getString("ftp_user", "anonymous");
        password = prefs.getString("ftp_pass", "");

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                currentPosition = position;

                // ⬅️ 关键改动：翻页时，立即释放并关掉上一个页面正在播放的视频，防止声音重叠
                if (adapter != null) {
                    adapter.releaseAllPlayers();
                }

                tryDownloadOriginalImage(position);
            }
        });

        tryDownloadOriginalImage(currentPosition);
    }


    private void tryDownloadOriginalImage(int position) {
        if (ImageAdapter.LoadedImageFtpUrisWhenClick == null || position >= ImageAdapter.LoadedImageFtpUrisWhenClick.size()) return;

        String ftpPath = ImageAdapter.LoadedImageFtpUrisWhenClick.get(position);
        if (ftpPath == null || ftpPath.isEmpty()) return;

        String fileName = ftpPath.substring(ftpPath.lastIndexOf("/") + 1);
        File targetFile = new File(cacheDir, fileName);

        if (targetFile.exists()) {
            updateMediaFile(position, targetFile.getAbsolutePath());
            Log.d("tryDownloadOriginalImage", "already existing:" + targetFile.getAbsolutePath());
            return;
        }

        // 💡 实例化监听器，处理 UI 更新
        DownloadProgressListener listener = new DownloadProgressListener() {
            @Override
            public void onProgress(String progressText) {
                // 在主线程更新进度条的文字或弹窗提示
                progressBar.setVisibility(View.VISIBLE);
                // 如果你用的是带文字的进度条可以直接 setText，或者用 Toast/TextView 展示
                // 例如：((ProgressDialog) progressBar).setMessage(progressText);
                Log.d("FTP_DOWNLOAD", progressText);
            }

            @Override
            public void onFinish(boolean success) {
                // 下载结束，隐藏进度条
                progressBar.setVisibility(View.GONE);
            }
        };

        executor.execute(() -> {
            // 把监听器传给下载方法
            boolean success = downloadFromFtp(ftpPath, targetFile, listener);
            if (success) {
                mainHandler.post(() -> {
                    updateMediaFile(position, targetFile.getAbsolutePath());

                    String mediaType = ImageAdapter.LoadedImageTypesWhenClick.get(position);
                    int tipRes = "VIDEO".equals(mediaType) ? R.string.original_video_loaded : R.string.original_image_loaded;
                    Toast.makeText(FullScreenActivity.this, tipRes, Toast.LENGTH_SHORT).show();
                });
            } else {
                mainHandler.post(() -> {
                    Toast.makeText(FullScreenActivity.this, R.string.failed_to_load, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // 💡 改造后的 downloadFromFtp 方法
    private boolean downloadFromFtp(String remoteFilePath, File localFile, DownloadProgressListener listener) {
        FTPClient ftpClient = new FTPClient();
        // boolean downloadSuccess = false; // ❌ 删掉这一行局部变量声明
        downloadSuccess = false; // ✅ 直接使用类的成员变量进行初始化

        try {
            ftpClient.connect(host, port);
            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) return false;

            boolean loginSuccess = ftpClient.login(user, password);
            if (!loginSuccess) return false;

            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setConnectTimeout(5000);
            ftpClient.setDataTimeout(30000);

            org.apache.commons.net.ftp.FTPFile remoteFile = ftpClient.mlistFile(remoteFilePath);
            long fileSize = 0;
            if (remoteFile != null) {
                fileSize = remoteFile.getSize();
            }

            try (FileOutputStream fos = new FileOutputStream(localFile);
                 InputStream inputStream = ftpClient.retrieveFileStream(remoteFilePath)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;
                long startTime = System.currentTimeMillis();
                long lastUpdate = startTime;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdate > 1000 && fileSize > 0) {
                        long timeElapsed = (currentTime - startTime) / 1000;
                        if (timeElapsed > 0) {
                            long speedBytesPerSec = totalBytesRead / timeElapsed;

                            String progressPercent = String.format("%.2f%%", (totalBytesRead * 100.0 / fileSize));
                            String speed = formatSize(speedBytesPerSec) + "/s";
                            String statusText = progressPercent + " - " + speed;

                            if (listener != null) {
                                mainHandler.post(() -> listener.onProgress(statusText));
                            }
                        }
                        lastUpdate = currentTime;
                    }
                }

                // 💡 这里直接给成员变量赋值
                downloadSuccess = ftpClient.completePendingCommand();
            }
            return downloadSuccess;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            // 💡 现在在 Lambda 中使用 downloadSuccess 就不会报错了
            if (listener != null) {
                mainHandler.post(() -> listener.onFinish(downloadSuccess));
            }

            try {
                if (ftpClient.isConnected()) {
                    ftpClient.logout();
                    ftpClient.disconnect();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (!downloadSuccess && localFile.exists()) {
                boolean deleted = localFile.delete();
                Log.d("FTP_DOWNLOAD", "Cleaned up failed/incomplete file: " + deleted);
            }
        }
    }

    // 💡 辅助方法：将字节数格式化为 KB/s 或 MB/s
    private static String formatSize(long sizeInBytes) {
        if (sizeInBytes < 1024) {
            return sizeInBytes + " B";
        } else if (sizeInBytes < 1024 * 1024) {
            return String.format("%.2f KB", sizeInBytes / 1024.0);
        } else {
            return String.format("%.2f MB", sizeInBytes / (1024.0 * 1024.0));
        }
    }


    private void updateMediaFile(int position, String localPath) {
        if (adapter != null) {
            adapter.updateMediaAt(position, localPath);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ⬅️ 手机切回桌面或来电时，暂停视频
        if (adapter != null) {
            adapter.releaseAllPlayers();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        viewPager.unregisterOnPageChangeCallback(null);

        if (adapter != null) {
            adapter.releaseAllPlayers(); // 释放最后的播放器实例
            adapter = null;
        }

        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }

        if (isFinishing()) {
            if (ImageAdapter.LoadedImageLocalUrisWhenClick != null) ImageAdapter.LoadedImageLocalUrisWhenClick.clear();
            if (ImageAdapter.LoadedImageFtpUrisWhenClick != null) ImageAdapter.LoadedImageFtpUrisWhenClick.clear();
            if (ImageAdapter.LoadedImageTypesWhenClick != null) ImageAdapter.LoadedImageTypesWhenClick.clear();
        }
    }
}