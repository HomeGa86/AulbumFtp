package com.echo2080.picsync;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ImageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_IMAGE = 1;

    public static List<ImageItem> LoadedImageItems = null;
    public static ArrayList<String> LoadedImageLocalUrisWhenClick = null;
    public static ArrayList<String> LoadedImageFtpUrisWhenClick = null;

    // ⬅️ 新增静态列表：传递到大图界面时，让大图界面能感知到哪一个是视频
    public static ArrayList<String> LoadedImageTypesWhenClick = null;

    private final Context context;
    private Set<Integer> selectedPositions = new HashSet<>();
    private boolean isSelectionMode = false;

    ImageAdapter(Context context, List<ImageItem> items) {
        this.context = context;
        LoadedImageItems = items;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_image, parent, false);
            return new ImageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ImageItem item = LoadedImageItems.get(position);

        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).textView.setText(item.text);
        } else if (holder instanceof ImageViewHolder) {
            ImageViewHolder imageHolder = (ImageViewHolder) holder;

            View selectionOverlay = imageHolder.itemView.findViewById(R.id.selection_overlay);
            View selectionCheck = imageHolder.itemView.findViewById(R.id.selection_check);

            // ⬅️ 新增：找到布局中的播放图标水印（请确保 item_image.xml 包含此 View 控件）
            View videoPlayIcon = imageHolder.itemView.findViewById(R.id.video_play_icon);

            boolean isSelected = selectedPositions.contains(position);
            if (isSelected) {
                selectionOverlay.setVisibility(View.VISIBLE);
                selectionCheck.setVisibility(View.VISIBLE);
            } else {
                selectionOverlay.setVisibility(View.GONE);
                selectionCheck.setVisibility(View.GONE);
            }

            // ⬅️ 动态根据媒体类型控制播放图标的可见性
            if (videoPlayIcon != null) {
                if (item.getFileType() == FileType.VIDEO) {
                    videoPlayIcon.setVisibility(View.VISIBLE); // 视频显示播放器三角箭头
                } else {
                    videoPlayIcon.setVisibility(View.GONE); // 图片隐藏
                }
            }

            Glide.with(imageHolder.imageView.getContext())
                    .load(item.getLocalUri())
                    .centerCrop()
                    .into(imageHolder.imageView);

            imageHolder.itemView.setOnLongClickListener(v -> {
                if (!isSelectionMode) {
                    isSelectionMode = true;
                    ((MainActivity) context).onItemLongSelected();
                }
                toggleSelection(position);
                return true;
            });

            imageHolder.itemView.setOnClickListener(v -> {
                if (isSelectionMode) {
                    toggleSelection(position);
                } else {
                    Intent intent = new Intent(context, FullScreenActivity.class);
                    LoadedImageLocalUrisWhenClick = new ArrayList<>();
                    LoadedImageFtpUrisWhenClick = new ArrayList<>();
                    LoadedImageTypesWhenClick = new ArrayList<>(); // ⬅️ 初始化

                    for (ImageItem img : LoadedImageItems) {
                        if (img.type == ImageItem.TYPE_IMAGE) {
                            LoadedImageLocalUrisWhenClick.add(img.getLocalUri());
                            LoadedImageFtpUrisWhenClick.add(img.getFtpPath());
                            // 将类型名字存入数组向下传递（"PICTURE" 或 "VIDEO"）
                            LoadedImageTypesWhenClick.add(img.getFileType().name());
                        }
                    }

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

    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position);

        if (selectedPositions.isEmpty()) {
            isSelectionMode = false;
            ((MainActivity) context).onSelectionFinished();
        }
    }

    public List<ImageItem> getSelectedItems() {
        List<ImageItem> selected = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            if (pos < LoadedImageItems.size()) {
                selected.add(LoadedImageItems.get(pos));
            }
        }
        return selected;
    }

    public void clearSelection() {
        selectedPositions.clear();
        isSelectionMode = false;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return LoadedImageItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return LoadedImageItems.get(position).type;
    }

    class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.header_text);
        }
    }

    class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.image_view);
        }
    }
}