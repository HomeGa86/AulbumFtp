package com.echo2080.picsync;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
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
    private ProgressBar progressBar; // 新增：声明进度条控件
    private TextView progressTextView; // 新增：声明文字控件
    private View progressContainer; // 新增：声明外层容器，方便统一隐藏
    private boolean downloadSuccess;
    private boolean isSftp;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen);

        // 💡 初始化你的进度条控件（假设你在 activity_full_screen.xml 里加了一个 id 为 download_progress_bar 的 ProgressBar）
        progressBar = findViewById(R.id.download_progress_bar);
        progressContainer = findViewById(R.id.progress_container);
        progressTextView = findViewById(R.id.download_progress_text);
        progressContainer.setVisibility(View.GONE);
        progressBar.setMax(100);




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
        isSftp = prefs.getBoolean("is_sftp", false);


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
            public void onProgress(int intProgress,String progressText) {
                progressContainer.setVisibility(View.VISIBLE);
                progressBar.setProgress(intProgress);
                progressTextView.setText(progressText);
                Log.d("FTP_DOWNLOAD", progressText);
            }

            @Override
            public void onFinish(boolean success) {
                // 下载结束，隐藏进度条
                progressContainer.setVisibility(View.GONE);
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
        // 假设你已经在类的成员变量中初始化好了 ftpClient (例如: FtpInterface ftpClient = new SftpHelper();)

        FtpInterface ftpHelper = null;
        if(isSftp)
        {
            ftpHelper = new SftpHelper();
        }
        else
        {
            ftpHelper = new FtpHelper();
        }

        downloadSuccess = false;

        try {
            if (!ftpHelper.connect(this)) {
                return false;
            }
            downloadSuccess = ftpHelper.downloadFile(remoteFilePath, localFile, listener);
            return downloadSuccess;

        } catch (Exception e) {
            e.printStackTrace();
            downloadSuccess = false;
            return false;
        } finally {
            ftpHelper.disconnect();
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