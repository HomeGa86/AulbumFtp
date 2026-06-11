package com.echo2080.picsync.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.echo2080.picsync.R;
import com.echo2080.picsync.Utils.FtpHelperProxy;
import com.echo2080.picsync.service.DownloadProgressListener;
import com.echo2080.picsync.ui.adapter.FullScreenPagerAdapter;
import com.echo2080.picsync.ui.adapter.ImageAdapter;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class FullScreenActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private FullScreenPagerAdapter adapter;
    private int currentPosition;

    private File cacheDir;
    // ✅ 固定3个并发线程，足够覆盖预加载窗口
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // ✅ 追踪所有进行中的下载任务
    private final Map<Integer, Future<?>> downloadTasks = new ConcurrentHashMap<>();

    // 💡 共享 FTP 连接，整个 Activity 生命周期内复用同一个连接
    private FtpHelperProxy sharedFtpHelper;
    private final Object ftpLock = new Object(); // 同步锁，防止并发下载互相干扰

    // ✅ 预加载窗口半径：只保留当前页 ±1 的下载，超出范围的自动取消
    private static final int PRELOAD_WINDOW = 1;

    private ProgressBar progressBar;
    private TextView progressTextView;
    private View progressContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen);

        progressBar = findViewById(R.id.download_progress_bar);
        progressContainer = findViewById(R.id.progress_container);
        progressTextView = findViewById(R.id.download_progress_text);
        if (progressContainer != null) progressContainer.setVisibility(View.GONE);
        if (progressBar != null) progressBar.setMax(100);

        Intent intent = getIntent();
        currentPosition = intent.getIntExtra("current_position", 0);

        cacheDir = new File(getCacheDir(), "full_images");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        viewPager = findViewById(R.id.view_pager);
        adapter = new FullScreenPagerAdapter(this,viewPager);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(currentPosition, false);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                currentPosition = position;

                if (adapter != null) adapter.releaseAllPlayers();

                // ✅ 核心修复：先清理窗口外的任务，再发起新下载
                cancelOutOfRangeDownloads(position);
                tryDownloadOriginalImage(position);
            }
        });

        // 初始加载
        cancelOutOfRangeDownloads(currentPosition);
        tryDownloadOriginalImage(currentPosition);
    }

    /**
     * ✅ 新增：取消预加载窗口之外的所有下载任务
     * 例如当前页=5，窗口=[4,6]，则取消 position<4 和 position>6 的所有任务
     */
    private void cancelOutOfRangeDownloads(int centerPosition) {
        int minValid = centerPosition - PRELOAD_WINDOW;
        int maxValid = centerPosition + PRELOAD_WINDOW;

        Iterator<Map.Entry<Integer, Future<?>>> iterator = downloadTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Future<?>> entry = iterator.next();
            int pos = entry.getKey();
            if (pos < minValid || pos > maxValid) {
                Future<?> future = entry.getValue();
                if (!future.isDone()) {
                    future.cancel(true);
                    Log.d("DownloadManager", "⛔ Cancelled out-of-window download at position: " + pos);
                }
                iterator.remove();
            }
        }
    }

    private void tryDownloadOriginalImage(int position) {
        if (ImageAdapter.LoadedImageFtpUrisWhenClick == null
                || position >= ImageAdapter.LoadedImageFtpUrisWhenClick.size()) return;

        String ftpPath = ImageAdapter.LoadedImageFtpUrisWhenClick.get(position);
        if (ftpPath == null || ftpPath.isEmpty()) return;

        // 💡 使用 ftpPath 的目录结构保存，避免不同目录下同名文件冲突
        String relativePath = ftpPath.startsWith("/") ? ftpPath.substring(1) : ftpPath;
        File targetFile = new File(cacheDir, relativePath);

        // 文件已缓存，直接更新UI
        if (targetFile.exists()) {
            updateMediaFile(position, targetFile.getAbsolutePath());
            Log.d("tryDownloadOriginalImage", "Cache hit: " + targetFile.getAbsolutePath());
            return;
        }

        // ✅ 如果该position已有正在运行的任务，不重复提交
        Future<?> existing = downloadTasks.get(position);
        if (existing != null && !existing.isDone()) {
            Log.d("tryDownloadOriginalImage", "Already downloading position: " + position);
            return;
        }

        // 显示进度条
        mainHandler.post(() -> {
            if (progressContainer != null) progressContainer.setVisibility(View.VISIBLE);
            if (progressBar != null) progressBar.setProgress(0);
            if (progressTextView != null) progressTextView.setText(getString(R.string.connecting));
        });

        DownloadProgressListener listener = new DownloadProgressListener() {
            @Override
            public void onProgress(int intProgress, String progressText) {
                mainHandler.post(() -> {
                    if (progressContainer != null) progressContainer.setVisibility(View.VISIBLE);
                    if (progressBar != null) progressBar.setProgress(intProgress);
                    if (progressTextView != null) progressTextView.setText(progressText);
                });
            }

            @Override
            public void onFinish(boolean success) {
                mainHandler.post(() -> {
                    if (progressContainer != null) progressContainer.setVisibility(View.GONE);
                });
            }
        };

        Future<?> future = executor.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;

            // 💡 下载前确保父目录存在
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            boolean success = downloadFromFtp(ftpPath, targetFile, listener);
            if (success && !Thread.currentThread().isInterrupted()) {
                mainHandler.post(() -> {
                    updateMediaFile(position, targetFile.getAbsolutePath());
                    String mediaType = ImageAdapter.LoadedImageTypesWhenClick.get(position);
                    int tipRes = "VIDEO".equals(mediaType)
                            ? R.string.original_video_loaded
                            : R.string.original_image_loaded;
                    Toast.makeText(FullScreenActivity.this, tipRes, Toast.LENGTH_SHORT).show();
                });
            } else if (!success && !Thread.currentThread().isInterrupted()) {
                mainHandler.post(() -> Toast.makeText(
                        FullScreenActivity.this, R.string.failed_to_load, Toast.LENGTH_SHORT).show());
            }
            // 任务结束后自动从map移除
            downloadTasks.remove(position);
        });

        downloadTasks.put(position, future);
    }

    /**
     * 💡 懒连接：使用全局单例 FTP 连接，App 整个生命周期内复用
     */
    private boolean ensureConnected() {
        sharedFtpHelper = FtpHelperProxy.getInstance(this);
        // 如果已连接（activeHelper 不为 null），直接返回 true
        if (sharedFtpHelper.getActiveHelper() != null) {
            return true;
        }
        return sharedFtpHelper.connect(this);
    }

    private boolean downloadFromFtp(String remoteFilePath, File localFile, DownloadProgressListener listener) {
        synchronized (ftpLock) {
            try {
                if (Thread.currentThread().isInterrupted()) return false;

                // 💡 复用已有连接，避免每次下载都重新握手
                if (!ensureConnected()) return false;

                boolean success = sharedFtpHelper.downloadFile(remoteFilePath, localFile, listener);

                // 如果下载失败，可能是连接断了，尝试重连后重试一次
                if (!success && !Thread.currentThread().isInterrupted()) {
                    Log.w("FullScreenActivity", "Download failed, attempting reconnect...");
                    if (sharedFtpHelper.reconnect(this)) {
                        success = sharedFtpHelper.downloadFile(remoteFilePath, localFile, listener);
                    }
                }

                return success;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            // 💡 注意：不在 finally 中 disconnect，保持连接供下次复用
        }
    }

    private void updateMediaFile(int position, String localPath) {
        if (adapter != null) adapter.updateMediaAt(position, localPath);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (adapter != null) adapter.releaseAllPlayers();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // ✅ 销毁时取消所有任务
        for (Future<?> future : downloadTasks.values()) {
            future.cancel(true);
        }
        downloadTasks.clear();
        executor.shutdownNow();

        // 💡 不断开全局单例连接，保留给其他页面复用
        // 连接会在 FTP 配置变更或 App 进程退出时被销毁

        if (adapter != null) {
            adapter.releaseAllPlayers();
            adapter = null;
        }

        if (isFinishing()) {
            if (ImageAdapter.LoadedImageLocalUrisWhenClick != null)
                ImageAdapter.LoadedImageLocalUrisWhenClick.clear();
            if (ImageAdapter.LoadedImageFtpUrisWhenClick != null)
                ImageAdapter.LoadedImageFtpUrisWhenClick.clear();
            if (ImageAdapter.LoadedImageTypesWhenClick != null)
                ImageAdapter.LoadedImageTypesWhenClick.clear();
        }
    }
}