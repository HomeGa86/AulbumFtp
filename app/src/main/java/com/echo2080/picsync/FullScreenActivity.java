package com.echo2080.picsync;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.net.ftp.FTPReply;

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
        //cacheDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "full_images");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        viewPager = findViewById(R.id.view_pager);
        Log.d("FullScreenActivity:onCreate localUris size:",String.valueOf(ImageAdapter.LoadedImageLocalUrisWhenClick.size()));
        adapter = new FullScreenPagerAdapter(this);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(currentPosition, false);

        SharedPreferences prefs = this.getSharedPreferences("AppSettings", MODE_PRIVATE);

        // 读取用户保存的配置，如果没有则使用默认值
        host = prefs.getString("ftp_host", "");
        port = Integer.parseInt(prefs.getString("ftp_port", "21"));
        user = prefs.getString("ftp_user", "anonymous");
        password = prefs.getString("ftp_pass", "");


        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                currentPosition = position;
                tryDownloadOriginalImage(position);
            }
        });

        tryDownloadOriginalImage(currentPosition);
    }

    /**
     * 尝试从 FTP 下载原图
     * 使用 ftpPaths 中对应的路径
     */
    private void tryDownloadOriginalImage(int position) {
        if (position >= ImageAdapter.LoadedImageFtpUrisWhenClick.size()) return;

        String ftpPath = ImageAdapter.LoadedImageFtpUrisWhenClick.get(position);
        if (ftpPath == null || ftpPath.isEmpty()) return;

        // 从 FTP 路径中提取文件名
        String fileName = ftpPath.substring(ftpPath.lastIndexOf("/") + 1);
        File targetFile = new File(cacheDir, fileName);

        // 如果已经下载过，直接使用缓存
        if (targetFile.exists()) {
            updateImage(position, targetFile.getAbsolutePath());
            Log.d("tryDownloadOriginalImage", "already existing:" + targetFile.getAbsolutePath());
            return;
        }

        // 后台下载
        executor.execute(() -> {
            boolean success = downloadFromFtp(ftpPath, targetFile);
            if (success) {
                mainHandler.post(() -> {
                    updateImage(position, targetFile.getAbsolutePath());
                    Toast.makeText(FullScreenActivity.this,
                            R.string.original_image_loaded, Toast.LENGTH_SHORT).show();
                });
            } else {
                mainHandler.post(() -> {
                    Toast.makeText(FullScreenActivity.this,
                            R.string.failed_to_load, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 从 FTP 下载文件
     * @param remoteFilePath FTP 上的完整路径（如 /images/2024/01/IMG_001.jpg）
     */
    private boolean downloadFromFtp(String remoteFilePath, File localFile) {
        FTPClient ftpClient = new FTPClient();
        boolean downloadSuccess = false; // Track actual success state

        try {
            ftpClient.connect(host, port);
            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                return false;
            }

            boolean loginSuccess = ftpClient.login(user, password);
            if (!loginSuccess) {
                return false;
            }

            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);

            // Set timeouts so the app doesn't hang indefinitely on bad networks
            ftpClient.setConnectTimeout(5000);
            ftpClient.setDataTimeout(10000);

            // Try-with-resources handles closing the stream automatically
            try (FileOutputStream fos = new FileOutputStream(localFile)) {
                downloadSuccess = ftpClient.retrieveFile(remoteFilePath, fos);
            }

            return downloadSuccess;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            // 1. Clean up FTP Connection
            try {
                if (ftpClient.isConnected()) {
                    ftpClient.logout();
                    ftpClient.disconnect();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            // 2. CRITICAL FIX: If download failed or threw exception, delete the broken file
            if (!downloadSuccess && localFile.exists()) {
                boolean deleted = localFile.delete();
                Log.d("FTP_DOWNLOAD", "Cleaned up failed/incomplete file: " + deleted);
            }
        }
    }

    private void updateImage(int position, String localPath) {
        adapter.updateImageAt(position, localPath);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 1. 取消 ViewPager 的页面改变监听，防止销毁过程中触发回调
        viewPager.unregisterOnPageChangeCallback(null);

        // 2. 清空 Adapter 的数据引用，防止内存泄漏
        if (adapter != null) {
            // 如果 FullScreenPagerAdapter 有提供清空数据的方法，可以在这里调用
            // 或者直接让 adapter 指向 null，帮助 GC 回收
            adapter = null;
        }

        // 3. 关闭后台下载线程池，防止退出后还在下载
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow(); // 使用 shutdownNow 尝试立即停止所有正在执行的任务
        }

        // 4. 【核心】清空静态大列表，释放内存
        if (isFinishing()) {
            if (ImageAdapter.LoadedImageLocalUrisWhenClick != null) {
                ImageAdapter.LoadedImageLocalUrisWhenClick.clear();
            }
            if (ImageAdapter.LoadedImageFtpUrisWhenClick != null) {
                ImageAdapter.LoadedImageFtpUrisWhenClick.clear();
            }
            Log.d("FullScreenActivity", "用户退出，已清空静态图片列表，释放内存");
        } else {
            Log.d("FullScreenActivity", "屏幕旋转，保留静态列表数据");
        }

        Log.d("FullScreenActivity", "已清空静态图片列表，释放内存");
    }
}