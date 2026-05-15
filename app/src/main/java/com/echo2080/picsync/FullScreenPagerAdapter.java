package com.echo2080.picsync;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView; // ✅ 修正 1: 修改了导入的包路径


public class FullScreenPagerAdapter extends RecyclerView.Adapter<FullScreenPagerAdapter.ViewHolder> {

    private Context context;

    public FullScreenPagerAdapter(Context context) {
        this.context = context;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_full_screen, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // 加上安全判断，防止列表被清空后越界
        if (ImageAdapter.LoadedImageLocalUrisWhenClick == null
                || position >= ImageAdapter.LoadedImageLocalUrisWhenClick.size()) {
            return;
        }

        String uri = ImageAdapter.LoadedImageLocalUrisWhenClick.get(position);
        Log.d("FullScreenAdapter", "position: " + position);
        Log.d("FullScreenAdapter", "正在加载图片路径: " + uri);

        // 核心修复：
        Glide.with(context)
                .load(uri) // 推荐直接用 File 对象，比 Uri.parse 更稳
                .dontTransform()     // 禁止 Glide 对图片进行缩放/形变，交给 PhotoView 处理
                .error(R.drawable.permissionx_ic_alert) // 可选：加载失败时显示一个错误图标，方便排查
                .into(holder.photoView);
    }

    @Override
    public int getItemCount() {
        return ImageAdapter.LoadedImageLocalUrisWhenClick == null ? 0 : ImageAdapter.LoadedImageLocalUrisWhenClick.size();
    }

    public void updateImageAt(int position, String newPath) {
        if (position >= 0 && position < ImageAdapter.LoadedImageLocalUrisWhenClick.size()) {
            ImageAdapter.LoadedImageLocalUrisWhenClick.set(position, newPath);
            notifyItemChanged(position);
        }
    }

    // ViewHolder 内部类
    static class ViewHolder extends RecyclerView.ViewHolder {
        // ✅ 修正 2: 声明变量时使用正确的类
        PhotoView photoView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            photoView = itemView.findViewById(R.id.photo_view);
        }
    }
}