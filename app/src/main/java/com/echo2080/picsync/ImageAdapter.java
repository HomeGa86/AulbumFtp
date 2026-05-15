package com.echo2080.picsync;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Context;


import android.content.Intent;


import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;


public class ImageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // 定义 View Type 常量
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_IMAGE = 1;

    public static List<ImageItem> LoadedImageItems = null;  // 改为 List<ImageItem>
    public static ArrayList<String> LoadedImageLocalUrisWhenClick = null;
    public static ArrayList<String> LoadedImageFtpUrisWhenClick = null;
    private final Context context;

    ImageAdapter(Context context,List<ImageItem> items) {
        this.context = context;
        this.LoadedImageItems = items;
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
        ImageItem item = LoadedImageItems.get(position);
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
                Intent intent = new Intent(context, FullScreenActivity.class);
                LoadedImageLocalUrisWhenClick = new ArrayList<>();
                LoadedImageFtpUrisWhenClick = new ArrayList<>();

                // 注意：这里传递的是整个列表，点击事件逻辑保持不变
                for (ImageItem img : LoadedImageItems) {
                    if (img.type == ImageItem.TYPE_IMAGE) { // 只传递图片项
                        LoadedImageLocalUrisWhenClick.add(img.getLocalUri());
                        LoadedImageFtpUrisWhenClick.add(img.getFtpPath());
                    }
                }

                // 计算点击位置在图片列表中的实际索引（需要跳过 Header）
                int imagePosition = 0;
                for (int i = 0; i < position; i++) {
                    if (LoadedImageItems.get(i).type == ImageItem.TYPE_IMAGE) {
                        imagePosition++;
                    }
                }
                intent.putExtra("current_position", imagePosition);
                context.startActivity(intent);
            });
        }
    }

    @Override
    public int getItemCount() {
        return LoadedImageItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return LoadedImageItems.get(position).type;
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