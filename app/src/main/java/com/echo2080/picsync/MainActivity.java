package com.echo2080.picsync;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.Context;

import android.media.ExifInterface;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;




import android.content.Intent;


import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AppSettings";
    private static final String KEY_IS_FIRST_RUN = "is_first_run";


    // 权限请求码（随便定义，只要不重复就行）
    private static final int PERMISSION_REQUEST_CODE = 100;

    private AppDatabase database;


    private RecyclerView recyclerView;
    private ImageAdapter adapter;
    private List<ImageItem> imagePathList = new ArrayList<>();

    private File thumbnailDir;


    // ⬅️ 添加广播接收器，用于监听同步完成事件
    private BroadcastReceiver syncCompleteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("MainActivity", "收到广播，开始加载图片");
            // 当同步服务下载了新图片后，刷新列表
            loadImages();
            Toast.makeText(MainActivity.this, R.string.sync_complete, Toast.LENGTH_SHORT).show();
        }
    };


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 第一步：检查并申请权限
        checkAndRequestPermission();

        // ⬅️ 注册广播接收器，监听同步完成事件
        LocalBroadcastManager.getInstance(this).registerReceiver(
                syncCompleteReceiver,
                new IntentFilter("com.echo2080.picsync.REFRESH_IMAGES")
        );


        database = AppDatabase.getInstance(this);
        thumbnailDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "thumbnails");


        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 3);
        // 【核心逻辑】设置 SpanSizeLookup，决定每个 Item 占几列
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // 获取当前位置的 Item 类型
                int type = imagePathList.get(position).type;
                // 如果是 Header，就让它占满 spanCount 列（即独占一行）
                // 如果是普通图片，就只占 1 列
                if (type == ImageItem.TYPE_HEADER) {
                    return 3;
                } else {
                    return 1;
                }
            }
        });

        // 初始化 RecyclerView
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(gridLayoutManager); // 3列网格
        adapter = new ImageAdapter(imagePathList);
        recyclerView.setAdapter(adapter);


    }

    // 增加 oldHost, oldPort 等参数，如果传 null 就代表是首次运行，使用默认值
    private void showFtpConfigDialog(final SharedPreferences prefs) {

        // 读取用户保存的配置，如果没有则使用默认值
        String oldHost = prefs.getString("ftp_host", "");
        Integer oldPort = Integer.parseInt(prefs.getString("ftp_port", "21"));
        String oldUser = prefs.getString("ftp_user", "anonymous");
        String oldPass = prefs.getString("ftp_pass", "");
        String oldBasePath = prefs.getString("ftp_base_path", "/");
        Boolean oldPassive = prefs.getBoolean("ftp_passive", true);



        View dialogView = getLayoutInflater().inflate(R.layout.dialog_ftp_config, null);
        final EditText etHost = dialogView.findViewById(R.id.et_ftp_host);
        final EditText etPort = dialogView.findViewById(R.id.et_ftp_port);
        final EditText etUser = dialogView.findViewById(R.id.et_ftp_user);
        final EditText etPass = dialogView.findViewById(R.id.et_ftp_pass);
        final EditText etBasePath = dialogView.findViewById(R.id.et_ftp_base_path);
        final CheckBox cbPassive = dialogView.findViewById(R.id.cb_passive_mode);

        // 【新增逻辑】如果有旧配置就填入旧配置，没有就使用默认值
        etHost.setText(oldHost != null ? oldHost : "");
        etPort.setText(oldPort != null ? oldPort.toString() : "21");
        etUser.setText(oldUser != null ? oldUser : "");
        etPass.setText(oldPass != null ? oldPass : "");
        etBasePath.setText(oldBasePath != null ? oldBasePath : "/");
        cbPassive.setChecked(oldPassive != null ? oldPassive : true);

        new AlertDialog.Builder(this)
                .setTitle(oldHost == null ? R.string.ftp_set : R.string.ftp_set) // 根据情况显示不同标题
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    // 保存数据到 SharedPreferences (逻辑保持不变)
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("ftp_host", etHost.getText().toString().trim());
                    editor.putString("ftp_port", etPort.getText().toString().trim());
                    editor.putString("ftp_user", etUser.getText().toString().trim());
                    editor.putString("ftp_pass", etPass.getText().toString().trim());
                    editor.putString("ftp_base_path", etBasePath.getText().toString().trim());
                    editor.putBoolean("ftp_passive", cbPassive.isChecked());
                    editor.putBoolean(KEY_IS_FIRST_RUN, false);
                    editor.apply();

                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                    startSyncServiceWithDelay(); // 保存后重启同步服务
                })
                // 【新增】如果是修改配置，建议允许用户点击“取消”直接关闭弹窗
                .setNegativeButton(oldHost == null ? null : R.string.cancel, null)
                .show();
    }

    /**
     * ⬅️ 启动后台同步服务
     */
    private void startSyncService() {
        Intent serviceIntent = new Intent(this, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ 必须使用 startForegroundService
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, R.string.sync_begin, Toast.LENGTH_SHORT).show();
    }

    // 加载右上角菜单
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    // 处理菜单点击事件
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            // 用户点击了“修改FTP设置”，弹出带有旧配置的对话框
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            showFtpConfigDialog(prefs);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    /**
     * 保存 FTP 路径到数据库
     */
    public void saveFtpPath(String localUri, String ftpPath) {
        new Thread(() -> {
            database.imageFtpDao().insert(new ImageFtpEntity(localUri, ftpPath));
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearOriginalImageCache();
        // ⬅️ 取消注册广播接收器
        LocalBroadcastManager.getInstance(this).unregisterReceiver(syncCompleteReceiver);
    }


    // 在 onCreate 的最后，注释掉原来的 startSyncService()，换成这个逻辑
    private void startSyncServiceWithDelay() {
        // 显示一个加载对话框，告诉用户正在同步
        // ProgressDialog dialog = new ProgressDialog(this);
        // dialog.setMessage("正在同步图片...");
        // dialog.setIndeterminate(true);
        // dialog.show();

        // 延迟 3 秒后启动服务，并在服务结束后加载图片
        // 注意：这仅用于演示原理，正式环境请用 BroadcastReceiver
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // 1. 启动服务
            Intent serviceIntent = new Intent(this, SyncService.class);
            startService(serviceIntent);

            // 2. 再延迟 5 秒（假设下载需要时间），然后加载图片
            // 这里的 5000ms 应该根据你的网络速度调整
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // dialog.dismiss();
                loadImages(); // 此时强制刷新界面
            }, 5000);
        }, 1000);
    }





    /**
     * 检查并申请读取图片的权限
     * Android 13+ 使用 READ_MEDIA_IMAGES
     * Android 10-12 使用 READ_EXTERNAL_STORAGE
     */
    private void checkAndRequestPermission() {
        // 根据 Android 版本选择正确的权限
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 检查是否已经获得授权
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                // 如果没有授权，则弹出系统权限请求框
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // 检查权限是否已经授予
        //if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
        if (1 == 1) {
            loadImages();
            // 1. 检查是否是首次运行
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true);

            if (isFirstRun) {
                // 如果是第一次，弹出配置对话框
                showFtpConfigDialog(prefs);
            }
            else
            {
                startSyncServiceWithDelay();
            }

        } else {
            // 权限未授予，需要向用户申请
            // 第二个参数可以传入多个权限，这里我们只申请一个
            ActivityCompat.requestPermissions(this,
                    new String[]{permission},
                    PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * 权限申请结果回调
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            // 检查授权结果
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户同意了，加载图片
                loadImages();
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                boolean isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true);

                if (isFirstRun) {
                    // 如果是第一次，弹出配置对话框
                    showFtpConfigDialog(prefs);
                }
                else
                {
                    startSyncServiceWithDelay();
                }
            } else {
                // 用户拒绝了，给个提示
                Toast.makeText(this, "需要存储权限才能显示图片", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadImages() {
        new Thread(() -> {
            List<ImageItem> loadedItems = new ArrayList<>();

            // 1. 递归遍历缩略图目录下的所有文件
            List<File> thumbnailFiles = new ArrayList<>();
            listFilesRecursively(thumbnailDir, thumbnailFiles);

            // 用于解析 EXIF 时间字符串的格式化器
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault());

            for (File file : thumbnailFiles) {
                String filePath = file.getAbsolutePath();
                Uri uri = Uri.fromFile(file);
                String uriString = uri.toString();

                // 2. 【新增】读取这张图片的拍摄时间
                long captureTime = 0;
                try {
                    ExifInterface exif = new ExifInterface(filePath);
                    // 获取 EXIF 中的原始拍摄时间字符串
                    String dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                    if (dateTime != null) {
                        // 将字符串转换为时间戳（毫秒）
                        Date date = dateFormat.parse(dateTime);
                        if (date != null) {
                            captureTime = date.getTime();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    // 如果读取 EXIF 失败，captureTime 默认为 0
                }

                // 如果 EXIF 里没有拍摄时间，可以退一步使用文件最后的修改时间
                if (captureTime == 0) {
                    captureTime = file.lastModified();
                }

                // 3. 创建 ImageItem 时，把拍摄时间传进去
                ImageItem item = new ImageItem(uriString, "", captureTime);
                loadedItems.add(item);
            }

            // 1. 排序（确保图片是按时间顺序排好的）
            Collections.sort(loadedItems, (item1, item2) -> Long.compare(item2.captureTime, item1.captureTime));

            // 2. 【新增】插入分组 Header
            List<ImageItem> finalList = new ArrayList<>();
            SimpleDateFormat groupFormat = new SimpleDateFormat("yyyy年MM月", Locale.CHINESE); // 2024年12月
            String lastGroupKey = "";

            for (ImageItem item : loadedItems) {
                // 将时间戳转换为 "2024-12" 格式用于分组判断
                String currentGroupKey = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date(item.captureTime));
                String displayGroupText = groupFormat.format(new Date(item.captureTime));

                // 如果是第一张图，或者月份变了，就插入一个新的 Header
                if (!currentGroupKey.equals(lastGroupKey)) {
                    Log.d("loadImages", "Header:" + displayGroupText);
                    finalList.add(new ImageItem(ImageItem.TYPE_HEADER, displayGroupText));
                    lastGroupKey = currentGroupKey;
                }

                // 添加图片项
                finalList.add(item);
                Log.d("loadImages", "Image:" + item.getLocalUri());
            }

            Log.d("loadImages", "finalList size:" + finalList.size());



            // 3. 回到主线程更新 UI
            runOnUiThread(() -> {
                imagePathList.clear();
                imagePathList.addAll(finalList); // 注意：这里 imagePathList 现在是 List<ImageItem> 类型
                adapter.notifyDataSetChanged();
                queryFtpPathsForAllImages();
            });
        }).start();
    }

    /**
     * 递归遍历目录下的所有文件
     */
    private void listFilesRecursively(File dir, List<File> fileList) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                listFilesRecursively(file, fileList);
            } else if (file.isFile()) {
                // 只添加图片文件
                String name = file.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".png") || name.endsWith(".webp")) {
                    fileList.add(file);
                }
            }
        }
    }

    private void clearOriginalImageCache() {
        // 这里的路径必须和你之前下载原图保存的路径完全一致
        File cacheDir = new File(getCacheDir(), "full_images");
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        file.delete(); // 删除目录下的所有文件
                    }
                }
            }
            // 如果目录本身也不需要保留，可以把下面这行注释取消掉
            // cacheDir.delete();
            Log.d("CacheClean", "原图缓存已清理完成");
        }
    }





    /**
     * 使用 LiveData 异步查询所有图片的 FTP 路径
     */
    private void queryFtpPathsForAllImages() {
        for (int i = 0; i < imagePathList.size(); i++) {
            ImageItem item = imagePathList.get(i);
            String localUri = item.getLocalUri();

            // 获取 LiveData 并观察它
            LiveData<String> ftpPathLiveData = database.imageFtpDao().getFtpPath(localUri);
            Log.d("MyTag", "getting ftp path for:" + i);
            ftpPathLiveData.observe(this, new Observer<String>() {
                @Override
                public void onChanged(String ftpPath) {
                    // 当数据库查询结果返回时，更新对应的 ImageItem
                    Log.d("MyTag", "queryFtpPathsForAllImages:" + ftpPath);

                    if (ftpPath != null) {
                        int index = imagePathList.indexOf(item);
                        Log.d("MyTag", "imagePathList index:" + index);
                        if (index >= 0) {
                            imagePathList.get(index).setFtpPath(ftpPath);
                            adapter.notifyItemChanged(index);
                        }
                    }
                    // 移除观察者，避免重复监听
                    ftpPathLiveData.removeObserver(this);
                }
            });
        }
    }


    // ==================== RecyclerView 适配器 ====================

    /**
     * 图片列表适配器
     */
    class ImageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        // 定义 View Type 常量
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_IMAGE = 1;

        private final List<ImageItem> items;  // 改为 List<ImageItem>

        ImageAdapter(List<ImageItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Log.d("onCreateViewHolder", "onCreateViewHolder begins");
            if (viewType == TYPE_HEADER) {
                Log.d("onCreateViewHolder", "Header");
                // 加载 Header 布局
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_header, parent, false);
                return new HeaderViewHolder(view);
            } else {
                Log.d("onCreateViewHolder", "image");
                // 加载 Image 布局
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_image, parent, false);
                return new ImageViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ImageItem item = items.get(position);
            Log.d("onBindViewHolder", "begins:" + position);

            if (holder instanceof HeaderViewHolder) {
                Log.d("onBindViewHolder", "Binding header:" + item.text);
                // 绑定 Header 数据
                ((HeaderViewHolder) holder).textView.setText(item.text);
            } else if (holder instanceof ImageViewHolder) {
                Log.d("onBindViewHolder", "准备加载图片: " + item.getLocalUri()); // 加上这行日志

                // 绑定 Image 数据
                ImageViewHolder imageHolder = (ImageViewHolder) holder;
                Glide.with(imageHolder.imageView.getContext())
                        .load(item.getLocalUri())
                        .centerCrop()
                        .into(imageHolder.imageView);

                imageHolder.itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(MainActivity.this, FullScreenActivity.class);
                    ArrayList<String> localUris = new ArrayList<>();
                    ArrayList<String> ftpPaths = new ArrayList<>();

                    // 注意：这里传递的是整个列表，点击事件逻辑保持不变
                    for (ImageItem img : items) {
                        if (img.type == ImageItem.TYPE_IMAGE) { // 只传递图片项
                            localUris.add(img.getLocalUri());
                            ftpPaths.add(img.getFtpPath());
                        }
                    }
                    intent.putStringArrayListExtra("local_uris", localUris);
                    intent.putStringArrayListExtra("ftp_paths", ftpPaths);

                    // 计算点击位置在图片列表中的实际索引（需要跳过 Header）
                    int imagePosition = 0;
                    for (int i = 0; i < position; i++) {
                        if (items.get(i).type == ImageItem.TYPE_IMAGE) {
                            imagePosition++;
                        }
                    }
                    intent.putExtra("current_position", imagePosition);
                    startActivity(intent);
                });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        // ViewHolder for Header
        class HeaderViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            HeaderViewHolder(@NonNull View itemView) {
                super(itemView);
                textView = itemView.findViewById(R.id.header_text);
            }
        }

        // ViewHolder for Image
        class ImageViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;
            ImageViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.image_view);
            }
        }


        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.image_view);
            }
        }
    }
}