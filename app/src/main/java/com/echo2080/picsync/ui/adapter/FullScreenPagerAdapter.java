package com.echo2080.picsync.ui.adapter;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.echo2080.picsync.R;
import com.github.chrisbanes.photoview.PhotoView;
import com.echo2080.picsync.ui.view.InterceptPhotoView;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class FullScreenPagerAdapter extends RecyclerView.Adapter<FullScreenPagerAdapter.ViewHolder> {

    private final ViewPager2 viewPager;
    private Context context;

    // 用来持有当前活动中的多个 VideoView 引用
    private Map<Integer, VideoView> activeVideoViews = new HashMap<>();

    public FullScreenPagerAdapter(Context context, ViewPager2 viewPager) {

        this.context = context;
        this.viewPager = viewPager;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_full_screen, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (ImageAdapter.LoadedImageLocalUrisWhenClick == null
                || position >= ImageAdapter.LoadedImageLocalUrisWhenClick.size()) {
            return;
        }

        String uriOrPath = ImageAdapter.LoadedImageLocalUrisWhenClick.get(position);
        String mediaType = "PICTURE";

        if (ImageAdapter.LoadedImageTypesWhenClick != null && position < ImageAdapter.LoadedImageTypesWhenClick.size()) {
            mediaType = ImageAdapter.LoadedImageTypesWhenClick.get(position);
        }

        Log.d("FullScreenAdapter", "正在绑定位置: " + position + ", 类型: " + mediaType + ", 原始路径: " + uriOrPath);

        // 🎬 分流处理 1：当前条目是视频
        if ("VIDEO".equals(mediaType)) {

            // 💡【核心修复】：利用 Android 系统的 Uri 类，安全地剥离掉可能存在的 "file:///" 协议头
            String absolutePath = null;
            try {
                if (uriOrPath.startsWith("file://")) {
                    absolutePath = Uri.parse(uriOrPath).getPath();
                } else {
                    absolutePath = uriOrPath; // 已经是纯绝对路径
                }
            } catch (Exception e) {
                Log.e("FullScreenAdapter", "解析路径失败", e);
            }

            // 💡【智能验证】：原视频大文件下载完后会存储在 cache 目录下的 "full_images" 文件夹中。
            // 只要绝对路径存在，且它不是放在 thumbnails（缩略图）目录中，就代表大视频文件已经就绪！
            if (absolutePath != null && !absolutePath.isEmpty() && new File(absolutePath).exists()
                    && !absolutePath.contains("/thumbnails/")) {

                Log.d("FullScreenAdapter", "检测到原视频已就绪，开始配置播放器: " + absolutePath);

                holder.photoView.setVisibility(View.GONE);
                holder.videoView.setVisibility(View.VISIBLE);

                activeVideoViews.put(position, holder.videoView);

                // VideoView 播放需要纯本地绝对路径，这里传入剥离协议头后的绝对路径
                holder.videoView.setVideoPath(absolutePath);

                MediaController mediaController = new MediaController(context);
                mediaController.setAnchorView(holder.videoView);
                holder.videoView.setMediaController(mediaController);

                holder.videoView.setOnPreparedListener(mp -> {
                    mp.setLooping(true);
                    holder.videoView.start();
                });

                holder.videoView.setOnErrorListener((mp, what, extra) -> {
                    Log.e("FullScreenAdapter", "视频播放出错, what: " + what + ", extra: " + extra);
                    return false;
                });

            } else {
                // 待机状态：原文件还未拉下来，直接把 file:///... 缩略图路径丢给 Glide 渲染封面
                Log.d("FullScreenAdapter", "原视频未就绪，使用 Glide 显示封面");
                holder.photoView.setVisibility(View.VISIBLE);
                holder.videoView.setVisibility(View.GONE);
                holder.photoView.setOnMatrixChangeListener(rect -> {
                    if (holder.photoView.getScale() > 1.0f) {
                        viewPager.setUserInputEnabled(false);
                    } else {
                        viewPager.setUserInputEnabled(true);
                    }
                });

                // 移除可能因为 ViewHolder 复用残留的视频控制层
                holder.videoView.stopPlayback();
                holder.videoView.setMediaController(null);

                Glide.with(context)
                        .load(uriOrPath) // Glide 本身就完美支持 file:/// 开头的字符串
                        .dontTransform()
                        .into(holder.photoView);
            }

        } else {
            // 🖼️ 分流处理 2：当前条目是普通图片
            holder.photoView.setVisibility(View.VISIBLE);
            holder.videoView.setVisibility(View.GONE);
            holder.photoView.setOnMatrixChangeListener(rect -> {
                // Determine if the user is currently zoomed in
                boolean isZoomed = holder.photoView.getScale() > 1.1f;

                // Disable the ViewPager2 swiping if zoomed
                // You may need to pass a reference of the ViewPager2 to your adapter
                viewPager.setUserInputEnabled(!isZoomed);
            });

// Important: Reset when a new page is selected or the view is recycled
            holder.photoView.setScale(1.0f);

            if (holder.videoView.isPlaying()) {
                holder.videoView.stopPlayback();
                holder.videoView.setMediaController(null);
            }

            Glide.with(context)
                    .load(uriOrPath)
                    .dontTransform()
                    .into(holder.photoView);
        }
    }

    @Override
    public int getItemCount() {
        return ImageAdapter.LoadedImageLocalUrisWhenClick == null ? 0 : ImageAdapter.LoadedImageLocalUrisWhenClick.size();
    }

    public void updateMediaAt(int position, String newPath) {
        if (position >= 0 && position < ImageAdapter.LoadedImageLocalUrisWhenClick.size()) {
            ImageAdapter.LoadedImageLocalUrisWhenClick.set(position, newPath);
            notifyItemChanged(position);
        }
    }

    public void releaseAllPlayers() {
        try {
            for (Map.Entry<Integer, VideoView> entry : activeVideoViews.entrySet()) {
                VideoView videoView = entry.getValue();
                if (videoView != null) {
                    videoView.stopPlayback();
                    videoView.setMediaController(null);
                    videoView.setVisibility(View.GONE);
                }
            }
            activeVideoViews.clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        InterceptPhotoView photoView;
        VideoView videoView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            photoView = itemView.findViewById(R.id.photo_view);
            videoView = itemView.findViewById(R.id.video_view);
        }
    }
}