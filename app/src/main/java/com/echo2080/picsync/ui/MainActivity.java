package com.echo2080.picsync.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
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
import com.echo2080.picsync.Database.AppDatabase;
import com.echo2080.picsync.Database.entity.DownloadedFileEntity;
import com.echo2080.picsync.R;
import com.echo2080.picsync.Utils.FtpHelperProxy;
import com.echo2080.picsync.Utils.FtpInterface;
import com.echo2080.picsync.Utils.LogHelper;
import com.echo2080.picsync.model.FileSuffixNames;
import com.echo2080.picsync.model.FileType;
import com.echo2080.picsync.model.ImageItem;
import com.echo2080.picsync.service.DownloadProgressListener;
import com.echo2080.picsync.service.SyncService;
import com.echo2080.picsync.ui.adapter.ImageAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AppSettings";
    private static final String KEY_IS_FIRST_RUN = "is_first_run";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private AppDatabase database;
    private RecyclerView recyclerView;
    private ImageAdapter adapter;
    private List<ImageItem> imagePathList = new ArrayList<>();
    private File thumbnailDir;

    private SyncService syncService;
    private boolean isBound = false;
    private View customScrollbarThumb;
    private float lastTouchY = -1f;
    private boolean isDraggingScrollbar = false;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();




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
        customScrollbarThumb = findViewById(R.id.custom_scrollbar_thumb);
        setupCustomScrollbar();

    }

    private void showFtpConfigDialog(final SharedPreferences prefs) {
        String oldHost = prefs.getString("ftp_host", "");
        Integer oldPort = Integer.parseInt(prefs.getString("ftp_port", "21").isEmpty() ? "21" : prefs.getString("ftp_port", "21"));
        String oldUser = prefs.getString("ftp_user", "anonymous");
        String oldPass = prefs.getString("ftp_pass", "");
        String oldBasePath = prefs.getString("ftp_base_path", "/");
        Boolean isSftp = prefs.getBoolean("is_sftp", false);

        String oldBackupHost = prefs.getString("backup_ftp_host", "");
        Integer oldBackupPort = Integer.parseInt(prefs.getString("backup_ftp_port", "21").isEmpty() ? "21" : prefs.getString("backup_ftp_port", "21"));
        Boolean isBackupSftp = prefs.getBoolean("backup_is_sftp", false);
        Boolean enableUpload = prefs.getBoolean("enable_upload", false);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_ftp_config, null);
        final EditText etHost = dialogView.findViewById(R.id.et_ftp_host);
        final EditText etPort = dialogView.findViewById(R.id.et_ftp_port);
        final EditText etUser = dialogView.findViewById(R.id.et_ftp_user);
        final EditText etPass = dialogView.findViewById(R.id.et_ftp_pass);
        final EditText etBasePath = dialogView.findViewById(R.id.et_ftp_base_path);
        final CheckBox cbIsSftp = dialogView.findViewById(R.id.cb_is_sftp);
        final EditText etBackupHost = dialogView.findViewById(R.id.et_backup_ftp_host);
        final EditText etBackupPort = dialogView.findViewById(R.id.et_backup_ftp_port);
        final CheckBox cbBackupIsSftp = dialogView.findViewById(R.id.cb_backup_is_sftp);
        final CheckBox cbEnableUpload = dialogView.findViewById(R.id.cb_enable_upload);

        etHost.setText(oldHost != null ? oldHost : "");
        etPort.setText(oldPort != null ? oldPort.toString() : "21");
        etUser.setText(oldUser != null ? oldUser : "");
        etPass.setText(oldPass != null ? oldPass : "");
        etBasePath.setText(oldBasePath != null ? oldBasePath : "/");
        cbIsSftp.setChecked(isSftp != null ? isSftp : false);

        etBackupHost.setText(oldBackupHost != null ? oldBackupHost : "");
        etBackupPort.setText(oldBackupPort != null ? oldBackupPort.toString() : "21");
        cbBackupIsSftp.setChecked(isBackupSftp != null ? isBackupSftp : false);
        cbEnableUpload.setChecked(enableUpload != null ? enableUpload : false);

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
                    editor.putBoolean("backup_is_sftp", cbBackupIsSftp.isChecked());
                    editor.putBoolean("enable_upload", cbEnableUpload.isChecked());
                    editor.apply();

                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();

                    new Thread(() -> {
                        database.serverFileDao().deleteAll();
                        Log.d("MainActivity", "FTP配置已更改，已清空服务器文件缓存表。");

                        FtpHelperProxy.resetLastSuccessType();
                        SyncService.resetFullSyncTimestamp(this);
                        startSyncServiceWithDelay();
                    }).start();
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
        else if (id == R.id.action_save) {
            downloadAndSaveSelectedImages();
            return true;
        }
        else if (id == R.id.action_delete_server) {
            deleteServerFiles();
            return true;
        }
        else if (id == R.id.action_copy_log) {
            LogHelper logHelper = new LogHelper(this);
            String logContent = logHelper.readAllLogFile(this);

            if (logContent == null || logContent.trim().isEmpty()) {
                Toast.makeText(this, getString(R.string.no_log), Toast.LENGTH_SHORT).show();
                return true;
            }

            copyToClipboard(logContent);
            return true;
        }


        return super.onOptionsItemSelected(item);
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("App Log", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.log_copied_toast, Toast.LENGTH_SHORT).show();
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

    /**
     * 将本地文件保存到系统相册（同时支持图片和视频）
     */
    private Uri saveToGallery(Context context, File sourceFile, String mimeType) {
        boolean isVideo = mimeType.startsWith("video/");

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.SIZE, sourceFile.length());
        // 图片和视频分别存到 DCIM/PicSync 下
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PicSync");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        // ✅ 关键：根据类型选择不同的 MediaStore 集合
        Uri contentUri = isVideo
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        Uri uri = resolver.insert(contentUri, values);
        if (uri == null) return null;

        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) throw new IOException("Failed to open output stream");

            FileInputStream fis = new FileInputStream(sourceFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            fis.close();

            // 写入完成，解除 PENDING 状态
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            e.printStackTrace();
            resolver.delete(uri, null, null);
            return null;
        }
    }

    private void downloadAndSaveSelectedImages() {
        List<ImageItem> selectedItems = adapter.getSelectedItems();
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, R.string.no_file_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        saveExecutor.execute(() -> {
            int successCount = 0;
            int failCount = 0;

            for (ImageItem item : selectedItems) {
                if (Thread.currentThread().isInterrupted()) break;

                String ftpPath = item.getFtpPath();
                String fileName = ftpPath.substring(ftpPath.lastIndexOf("/") + 1);

                File tempFile = new File(getCacheDir(), "AlbumFtp_" + fileName);

                boolean downloaded = downloadFromFtp(ftpPath, tempFile, null);

                if (downloaded && tempFile.exists()) {
                    String mime = getMimeType(fileName);
                    Uri galleryUri = saveToGallery(this, tempFile, mime);


                    if (galleryUri != null) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } else {
                    failCount++;
                }
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }

            final int s = successCount;
            final int f = failCount;
            runOnUiThread(() -> {
                adapter.clearSelection();
                adapter.notifyDataSetChanged();

                String msg;
                if (f == 0) {
                    msg = MessageFormat.format(getString(R.string.saved_to_gallery), s);
                } else {
                    msg = MessageFormat.format(getString(R.string.save_partial_result), s, f);
                }
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void deleteServerFiles() {
        List<ImageItem> selectedItems = adapter.getSelectedItems();
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, R.string.no_file_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_server_file)
                .setMessage(R.string.confirm_delete_server_files)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    saveExecutor.execute(() -> {
                        int successCount = 0;
                        int failCount = 0;

                        for (ImageItem item : selectedItems) {
                            if (Thread.currentThread().isInterrupted()) break;

                            String ftpPath = item.getFtpPath();
                            String localUri = item.getLocalUri();
                            
                            if (ftpPath == null || ftpPath.isEmpty()) {
                                failCount++;
                                continue;
                            }

                            FtpInterface ftpHelper = new FtpHelperProxy(this);
                            try {
                                if (!ftpHelper.connect(this)) {
                                    failCount++;
                                    continue;
                                }

                                boolean deleted = ftpHelper.deleteFile(ftpPath);
                                if (deleted) {
                                    successCount++;
                                    
                                    String thumbnailPath = Uri.parse(localUri).getPath();
                                    if (thumbnailPath != null) {
                                        File thumbnailFile = new File(thumbnailPath);
                                        if (thumbnailFile.exists()) {
                                            thumbnailFile.delete();
                                        }
                                    }
                                    
                                    database.downloadedFileDao().markAsDeleted(ftpPath);
                                    database.imageFtpDao().deleteByFtpPath(ftpPath);
                                } else {
                                    failCount++;
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                failCount++;
                            } finally {
                                ftpHelper.disconnect();
                            }
                        }

                        final int s = successCount;
                        final int f = failCount;
                        runOnUiThread(() -> {
                            adapter.clearSelection();

                            String msg;
                            if (f == 0) {
                                msg = MessageFormat.format(getString(R.string.server_files_deleted), s);
                            } else {
                                msg = getString(R.string.failed_to_delete_server_files);
                            }
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();

                            loadImages();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private boolean downloadFromFtp(String remoteFilePath, File localFile, DownloadProgressListener listener) {
        FtpInterface ftpHelper = new FtpHelperProxy(this);
        try {
            if (Thread.currentThread().isInterrupted()) return false;
            if (!ftpHelper.connect(this)) return false;
            return ftpHelper.downloadFile(remoteFilePath, localFile, listener);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            ftpHelper.disconnect();
        }
    }




    public void onItemLongSelected() {
        Toast.makeText(this, R.string.select_file, Toast.LENGTH_SHORT).show();
    }

    public void onSelectionFinished() {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearOriginalImageCache();
        saveExecutor.shutdownNow();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)
            String[] permissions = {
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.POST_NOTIFICATIONS
            };
            
            boolean hasAllPermissions = true;
            for (String permission : permissions) {
                if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    hasAllPermissions = false;
                    break;
                }
            }
            
            if (!hasAllPermissions) {
                requestPermissions(permissions, PERMISSION_REQUEST_CODE);
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 (API 29-32)
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE
                );
                return;
            }
        }
        
        // 权限已授予，继续执行
        loadImages();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true);
        if (isFirstRun) {
            showFtpConfigDialog(prefs);
        } else {
            startSyncServiceWithDelay();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show();
                loadImages();
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                boolean isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true);
                if (isFirstRun) {
                    showFtpConfigDialog(prefs);
                } else {
                    startSyncServiceWithDelay();
                }
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
            }
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
            Locale currentLocale = Locale.getDefault();
            boolean isChinese = currentLocale.getLanguage().equals(Locale.CHINESE.getLanguage())
                    || currentLocale.equals(Locale.SIMPLIFIED_CHINESE)
                    || currentLocale.equals(Locale.TRADITIONAL_CHINESE);

            SimpleDateFormat groupFormat = isChinese
                    ? new SimpleDateFormat("yyyy年MM月", Locale.CHINESE)
                    : new SimpleDateFormat("MMMM yyyy", Locale.ENGLISH);

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
                recyclerView.postDelayed(this::updateThumbPosition, 200);
            });
        }).start();
    }

    private void listFilesRecursively(File dir, List<File> fileList) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // 递归遍历子目录
                listFilesRecursively(file, fileList);
            } else if (file.isFile()) {
                String fileName = file.getName();
                if (FileSuffixNames.isImage(fileName)) {
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


    private final SyncService.OnDataUpdateListener dataListener = dataFromSyncService -> {
        runOnUiThread(() -> {
            setTitle(dataFromSyncService);
        });
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SyncService.LocalBinder binder = (SyncService.LocalBinder) service;
            syncService = binder.getService();
            isBound = true;

            // 注册监听
            syncService.registerDataListener(dataListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            syncService = null;
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, SyncService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            // 即使 onStart/onStop 多次调用，unregister 也能准确移除当前的监听器
            syncService.unregisterDataListener(dataListener);
            unbindService(connection);
            isBound = false;
        }
    }

    private void setupCustomScrollbar() {
        // 1. 监听 RecyclerView 滑动 → 更新滑块位置（仅在非拖动状态下生效）
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!isDraggingScrollbar) {
                    updateThumbPosition();
                }
            }
        });

        // 2. 监听滑块触摸事件 → 拖动滑块控制 RecyclerView
        customScrollbarThumb.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    isDraggingScrollbar = true;
                    lastTouchY = event.getRawY();
                    v.setAlpha(1.0f);
                    // 💡 FIX: 防止 RecyclerView 和父容器拦截后续触摸事件（Android 12+ 必须）
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float currentY = event.getRawY();
                    float deltaY = currentY - lastTouchY;
                    lastTouchY = currentY;

                    // 获取当前滑块位置和轨道参数
                    float currentThumbY = v.getY();
                    float trackHeight = ((View) v.getParent()).getHeight() - v.getHeight();

                    // 计算新的滑块Y坐标（限制在轨道范围内）
                    float newThumbY = Math.max(0, Math.min(currentThumbY + deltaY, trackHeight));

                    // 直接同步设置滑块位置
                    v.setY(newThumbY);

                    // 根据新位置反算列表应该滚动到的绝对偏移量
                    int scrollRange = recyclerView.computeVerticalScrollRange();
                    int scrollExtent = recyclerView.computeVerticalScrollExtent();
                    int maxScroll = scrollRange - scrollExtent;

                    if (trackHeight > 0 && maxScroll > 0) {
                        float ratio = newThumbY / trackHeight;
                        int targetOffset = (int) (ratio * maxScroll);

                        int currentOffset = recyclerView.computeVerticalScrollOffset();
                        int actualDelta = targetOffset - currentOffset;
                        if (actualDelta != 0) {
                            recyclerView.scrollBy(0, actualDelta);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDraggingScrollbar = false;
                    lastTouchY = -1f;
                    v.setAlpha(0.7f);
                    // 手指抬起时，强制同步一次位置，消除最后的跳变
                    updateThumbPosition();
                    return true;
            }
            return false;
        });

        // 3. 初始延迟更新一次位置
        recyclerView.postDelayed(this::updateThumbPosition, 300);
    }


    private void updateThumbPosition() {
        if (customScrollbarThumb == null || recyclerView == null) return;

        int scrollRange = recyclerView.computeVerticalScrollRange();
        int scrollOffset = recyclerView.computeVerticalScrollOffset();
        int scrollExtent = recyclerView.computeVerticalScrollExtent();
        int maxScroll = scrollRange - scrollExtent;

        if (maxScroll <= 0) {
            customScrollbarThumb.setVisibility(View.GONE);
            return;
        }
        customScrollbarThumb.setVisibility(View.VISIBLE);

        float trackHeight = ((View) customScrollbarThumb.getParent()).getHeight() - customScrollbarThumb.getHeight();
        float ratio = (float) scrollOffset / maxScroll;
        float newY = ratio * trackHeight;

        customScrollbarThumb.setY(Math.max(0, Math.min(newY, trackHeight)));
    }


    private String getMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";

        String lower = fileName.toLowerCase();

        // 图片类型
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";

        // 视频类型
        if (lower.endsWith(".mp4"))  return "video/mp4";
        if (lower.endsWith(".mkv"))  return "video/x-matroska";
        if (lower.endsWith(".mov"))  return "video/quicktime";
        if (lower.endsWith(".avi"))  return "video/x-msvideo";
        if (lower.endsWith(".3gp"))  return "video/3gpp";
        if (lower.endsWith(".hevc")) return "video/hevc";
        if (lower.endsWith(".h265")) return "video/hevc";

        // ⬅️ 新增的视频类型
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".flv"))  return "video/x-flv";
        if (lower.endsWith(".wmv"))  return "video/x-ms-wmv";
        if (lower.endsWith(".mpeg") || lower.endsWith(".mpg")) return "video/mpeg";
        if (lower.endsWith(".ts"))   return "video/mp2t";

        // 默认返回二进制流
        return "application/octet-stream";
    }

}