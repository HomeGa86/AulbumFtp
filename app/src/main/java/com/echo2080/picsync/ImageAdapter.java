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
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class ImageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // 定义 View Type 常量
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_IMAGE = 1;

    public static List<ImageItem> LoadedImageItems = null;  // 改为 List<ImageItem>
    public static ArrayList<String> LoadedImageLocalUrisWhenClick = null;
    public static ArrayList<String> LoadedImageFtpUrisWhenClick = null;
    private final Context context;
    // 【新增】用于存储被选中的图片索引（或者直接存对象），这里用 Set 存储位置索引
    private Set<Integer> selectedPositions = new HashSet<>();
    // 【新增】用于判断是否处于“选择模式”
    private boolean isSelectionMode = false;


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

            // 1. 获取遮罩层和对勾图标的控件
            View selectionOverlay = imageHolder.itemView.findViewById(R.id.selection_overlay);
            View selectionCheck = imageHolder.itemView.findViewById(R.id.selection_check);

            // 2. 【核心代码】根据选中状态更新 UI
            boolean isSelected = selectedPositions.contains(position);
            if (isSelected) {
                selectionOverlay.setVisibility(View.VISIBLE); // 显示半透明遮罩
                selectionCheck.setVisibility(View.VISIBLE);   // 显示对勾图标
            } else {
                selectionOverlay.setVisibility(View.GONE);    // 隐藏遮罩
                selectionCheck.setVisibility(View.GONE);      // 隐藏对勾
            }

            Glide.with(imageHolder.imageView.getContext())
                    .load(item.getLocalUri())
                    .centerCrop()
                    .into(imageHolder.imageView);


            // 【新增】设置长按监听器，进入选择模式并选中当前项
            imageHolder.itemView.setOnLongClickListener(v -> {
                if (!isSelectionMode) {
                    // 进入选择模式
                    isSelectionMode = true;
                    // 在 Activity 中更新 ActionMode (需要回调，这里先假设有一个方法 updateActionMode())
                    // 我们稍后会在 MainActivity 中处理这个逻辑
                    ((MainActivity)context).onItemLongSelected();
                }
                // 切换选中状态
                toggleSelection(position);
                return true; // 消费长按事件
            });

            // 【新增】设置点击监听器（普通点击和选中点击）
            imageHolder.itemView.setOnClickListener(v -> {
                if (isSelectionMode) {
                    // 如果是选择模式，点击是用于选择/取消选择
                    toggleSelection(position);
                } else {
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

                }
            });




        }
    }

    // 【新增】切换选中状态的方法
    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position); // 刷新该项的 UI

        // 如果取消选中后，选中列表为空，则退出选择模式
        if (selectedPositions.isEmpty()) {
            isSelectionMode = false;
            // 通知 Activity 退出 ActionMode
            ((MainActivity)context).onSelectionFinished();
        }
    }

    // 【新增】获取当前选中的图片列表
    public List<ImageItem> getSelectedItems() {
        List<ImageItem> selected = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            if (pos < LoadedImageItems.size()) {
                selected.add(LoadedImageItems.get(pos));
            }
        }
        return selected;
    }

    // 【新增】清除所有选中状态
    public void clearSelection() {
        selectedPositions.clear();
        isSelectionMode = false;
        notifyDataSetChanged(); // 简单粗暴刷新，或者优化为只刷新变化的项
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