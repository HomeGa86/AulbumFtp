package com.echo2080.picsync;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AppSettings";
    private static final String KEY_IS_FIRST_RUN = "is_first_run";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private AppDatabase database;
    private RecyclerView recyclerView;
    private ImageAdapter adapter;
    private List<ImageItem> imagePathList = new ArrayList<>();
    private File thumbnailDir;

    private BroadcastReceiver syncCompleteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("MainActivity", "收到广播，开始加载媒体文件");
            loadImages();
            Toast.makeText(MainActivity.this, R.string.sync_complete, Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        thumbnailDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "thumbnails");
        database = AppDatabase.getInstance(this);

        checkAndRequestPermission();

        LocalBroadcastManager.getInstance(this).registerReceiver(
                syncCompleteReceiver,
                new IntentFilter("com.echo2080.picsync.REFRESH_IMAGES")
        );

        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 3);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int type = imagePathList.get(position).type;
                if (type == ImageItem.TYPE_HEADER) {
                    return 3;
                } else {
                    return 1;
                }
            }
        });

        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(gridLayoutManager);
        adapter = new ImageAdapter(this, imagePathList);
        recyclerView.setAdapter(adapter);
    }

    private void showFtpConfigDialog(final SharedPreferences prefs) {
        String oldHost = prefs.getString("ftp_host", "");
        Integer oldPort = Integer.parseInt(prefs.getString("ftp_port", "21"));
        String oldUser = prefs.getString("ftp_user", "anonymous");
        String oldPass = prefs.getString("ftp_pass", "");
        String oldBasePath = prefs.getString("ftp_base_path", "/");
        Boolean isSftp = prefs.getBoolean("is_sftp", false);

        String oldBackupHost = prefs.getString("backup_ftp_host", "");
        Integer oldBackupPort = Integer.parseInt(prefs.getString("backup_ftp_port", "21"));

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_ftp_config, null);
        final EditText etHost = dialogView.findViewById(R.id.et_ftp_host);
        final EditText etPort = dialogView.findViewById(R.id.et_ftp_port);
        final EditText etUser = dialogView.findViewById(R.id.et_ftp_user);
        final EditText etPass = dialogView.findViewById(R.id.et_ftp_pass);
        final EditText etBasePath = dialogView.findViewById(R.id.et_ftp_base_path);
        final CheckBox cbIsSftp = dialogView.findViewById(R.id.cb_is_sftp);
        final EditText etBackupHost = dialogView.findViewById(R.id.et_backup_ftp_host);
        final EditText etBackupPort = dialogView.findViewById(R.id.et_backup_ftp_port);


        etHost.setText(oldHost != null ? oldHost : "");
        etPort.setText(oldPort != null ? oldPort.toString() : "21");
        etUser.setText(oldUser != null ? oldUser : "");
        etPass.setText(oldPass != null ? oldPass : "");
        etBasePath.setText(oldBasePath != null ? oldBasePath : "/");
        cbIsSftp.setChecked(isSftp != null ? isSftp : false);

        etBackupHost.setText(oldBackupHost != null ? oldBackupHost : "");
        etBackupPort.setText(oldBackupPort != null ? oldBackupPort.toString() : "21");

        new AlertDialog.Builder(this)
                .setTitle(R.string.ftp_set)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("ftp_host", etHost.getText().toString().trim());
                    editor.putString("ftp_port", etPort.getText().toString().trim());
                    editor.putString("ftp_user", etUser.getText().toString().trim());
                    editor.putString("ftp_pass", etPass.getText().toString().trim());
                    editor.putString("ftp_base_path", etBasePath.getText().toString().trim());
                    editor.putBoolean("is_sftp", cbIsSftp.isChecked());
                    editor.putBoolean(KEY_IS_FIRST_RUN, false);
                    editor.putString("backup_ftp_host", etBackupHost.getText().toString().trim());
                    editor.putString("backup_ftp_port", etBackupPort.getText().toString().trim());
                    editor.apply();

                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                    SyncService.resetFullSyncTimestamp(this);
                    startSyncServiceWithDelay();
                })
                .setNegativeButton(oldHost == null ? null : R.string.cancel, null)
                .show();
    }

    private void startSyncService() {
        Intent serviceIntent = new Intent(this, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, R.string.sync_begin, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            showFtpConfigDialog(prefs);
            return true;
        } else if (id == R.id.action_delete) {
            deleteSelectedImages();
            return true;
        }
        else if (id == R.id.action_view_log) {
            showLogDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showLogDialog() {
        // 1. 创建 AlertDialog.Builder
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.view_log));

        // 2. 创建一个 TextView 用于显示文本
        TextView logTextView = new TextView(this);

        // 3. 调用你已有的工具类获取日志
        // 注意：这里传入的是 this (即 Activity Context)
        LogHelper logHelper = new LogHelper(this);
        String logContent = logHelper.readLogFile(this);

        logTextView.setText(logContent);
        logTextView.setPadding(50, 30, 30, 30);

        logTextView.setTextColor(Color.BLACK);

        logTextView.setTextSize(12);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logTextView);

        builder.setView(scrollView);

        builder.setPositiveButton(getString(R.string.close), (dialog, which) -> dialog.dismiss());

        builder.show();
    }


    private void deleteSelectedImages() {
        List<ImageItem> selectedItems = adapter.getSelectedItems();
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, R.string.no_file_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            for (ImageItem item : selectedItems) {
                String uriString = item.getLocalUri();
                String absolutePath = Uri.parse(uriString).getPath();
                File file = new File(absolutePath);
                if (file.exists()) {
                    file.delete();
                }
                database.downloadedFileDao().markAsDeleted(item.getFtpPath());
            }

            runOnUiThread(() -> {
                ImageAdapter.LoadedImageItems.removeAll(selectedItems);
                adapter.notifyDataSetChanged();
                adapter.clearSelection();
                Toast.makeText(MainActivity.this, MessageFormat.format(getString(R.string.deleted), selectedItems.size()), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    public void onItemLongSelected() {
        Toast.makeText(this, R.string.select_file, Toast.LENGTH_SHORT).show();
    }

    public void onSelectionFinished() {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearOriginalImageCache();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(syncCompleteReceiver);
    }

    private void startSyncServiceWithDelay() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent serviceIntent = new Intent(this, SyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }, 1000);
    }

    private void checkAndRequestPermission() {
        loadImages();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true);
        if (isFirstRun) {
            showFtpConfigDialog(prefs);
        } else {
            startSyncServiceWithDelay();
        }
    }

    private void loadImages() {
        new Thread(() -> {
            List<ImageItem> loadedItems = new ArrayList<>();

            long startTime = System.currentTimeMillis();
            List<DownloadedFileEntity> allDbFiles = database.downloadedFileDao().getAllDownloadedFiles();

            // ⬅️ 优化：Map 存储对应的 Entity 对象，方便同时检索时间和 FileType
            Map<String, DownloadedFileEntity> dbFileMap = new HashMap<>();
            for (DownloadedFileEntity entity : allDbFiles) {
                dbFileMap.put(entity.getLocalThumbnailPath(), entity);
            }

            List<File> thumbnailFiles = new ArrayList<>();
            listFilesRecursively(thumbnailDir, thumbnailFiles);

            for (File file : thumbnailFiles) {
                String filePath = file.getAbsolutePath();
                String uriString = Uri.fromFile(file).toString();

                long captureTime = file.lastModified();
                FileType fileType = FileType.PICTURE; // 默认

                // 从本地映射表找回原本入库的属性
                if (dbFileMap.containsKey(filePath)) {
                    DownloadedFileEntity entity = dbFileMap.get(filePath);
                    if (entity != null) {
                        captureTime = entity.getCaptureTime();
                        fileType = entity.getFileType(); // 获取文件枚举类型
                    }
                }

                // 传入正确的 FileType
                ImageItem item = new ImageItem(uriString, "", captureTime, fileType);
                loadedItems.add(item);
            }

            Collections.sort(loadedItems, (item1, item2) -> Long.compare(item2.captureTime, item1.captureTime));

            List<ImageItem> finalList = new ArrayList<>();
            SimpleDateFormat groupFormat = new SimpleDateFormat("yyyy年MM月", Locale.CHINESE);
            String lastGroupKey = "";

            for (ImageItem item : loadedItems) {
                String currentGroupKey = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date(item.captureTime));
                String displayGroupText = groupFormat.format(new Date(item.captureTime));

                if (!currentGroupKey.equals(lastGroupKey)) {
                    finalList.add(new ImageItem(ImageItem.TYPE_HEADER, displayGroupText));
                    lastGroupKey = currentGroupKey;
                }
                finalList.add(item);
            }

            runOnUiThread(() -> {
                imagePathList.clear();
                imagePathList.addAll(finalList);
                adapter.notifyDataSetChanged();
                queryFtpPathsForAllImages();
            });
        }).start();
    }

    private void listFilesRecursively(File dir, List<File> fileList) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                listFilesRecursively(file, fileList);
            } else if (file.isFile()) {
                String name = file.getName().toLowerCase();
                // ⬅️ 修改：除了图片，还将缩略图文件夹内可能由于后缀名生成的视频相关缩略图纳入
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".png") || name.endsWith(".webp")) {
                    fileList.add(file);
                }
            }
        }
    }

    private void clearOriginalImageCache() {
        File cacheDir = new File(getCacheDir(), "full_images");
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) file.delete();
                }
            }
        }
    }

    private void queryFtpPathsForAllImages() {
        for (int i = 0; i < imagePathList.size(); i++) {
            ImageItem item = imagePathList.get(i);
            if (item.type == ImageItem.TYPE_HEADER) continue;
            String localUri = item.getLocalUri();

            LiveData<String> ftpPathLiveData = database.imageFtpDao().getFtpPath(localUri);
            final int index = i;
            ftpPathLiveData.observe(this, new Observer<String>() {
                @Override
                public void onChanged(String ftpPath) {
                    if (ftpPath != null && index < imagePathList.size()) {
                        imagePathList.get(index).setFtpPath(ftpPath);
                    }
                    ftpPathLiveData.removeObserver(this);
                }
            });
        }
    }
}