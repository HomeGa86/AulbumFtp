package com.echo2080.picsync;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen);

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

        // 如果缓存已存在
        if (targetFile.exists()) {
            updateMediaFile(position, targetFile.getAbsolutePath());
            Log.d("tryDownloadOriginalImage", "already existing:" + targetFile.getAbsolutePath());
            return;
        }

        executor.execute(() -> {
            boolean success = downloadFromFtp(ftpPath, targetFile);
            if (success) {
                mainHandler.post(() -> {
                    updateMediaFile(position, targetFile.getAbsolutePath());

                    // 根据类型提示不同的文本
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

    private boolean downloadFromFtp(String remoteFilePath, File localFile) {
        FTPClient ftpClient = new FTPClient();
        boolean downloadSuccess = false;

        try {
            ftpClient.connect(host, port);
            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) return false;

            boolean loginSuccess = ftpClient.login(user, password);
            if (!loginSuccess) return false;

            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);

            ftpClient.setConnectTimeout(5000);
            ftpClient.setDataTimeout(30000); // 💡 视频文件通常较大，将 DataTimeout 适当调大到 30秒 保证稳定性

            try (FileOutputStream fos = new FileOutputStream(localFile)) {
                downloadSuccess = ftpClient.retrieveFile(remoteFilePath, fos);
            }
            return downloadSuccess;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
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