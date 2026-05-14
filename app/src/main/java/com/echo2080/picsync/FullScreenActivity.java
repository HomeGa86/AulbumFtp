package com.echo2080.picsync;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.net.ftp.FTPReply;

public class FullScreenActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private FullScreenPagerAdapter adapter;
    private ArrayList<String> localUris;   // 本地缩略图 URI 列表
    private ArrayList<String> ftpPaths;    // FTP 路径列表
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
        localUris = intent.getStringArrayListExtra("local_uris");
        ftpPaths = intent.getStringArrayListExtra("ftp_paths");
        currentPosition = intent.getIntExtra("current_position", 0);

        cacheDir = new File(getCacheDir(), "full_images");
        //cacheDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "full_images");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        viewPager = findViewById(R.id.view_pager);
        Log.d("FullScreenActivity:onCreate localUris size:",String.valueOf(localUris.size()));
        adapter = new FullScreenPagerAdapter(this, localUris);
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
        if (position >= ftpPaths.size()) return;

        String ftpPath = ftpPaths.get(position);
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

            // 使用完整的远程路径下载
            try (FileOutputStream fos = new FileOutputStream(localFile)) {
                return ftpClient.retrieveFile(remoteFilePath, fos);
            }

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
        }
    }

    private void updateImage(int position, String localPath) {
        adapter.updateImageAt(position, localPath);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}