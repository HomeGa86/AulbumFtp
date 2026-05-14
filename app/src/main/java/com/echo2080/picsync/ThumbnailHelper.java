package com.echo2080.picsync;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.ThumbnailUtils;

import android.graphics.BitmapFactory;
import android.util.Log;


import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.media.ExifInterface;


public class ThumbnailHelper {
    private static final String TAG = "ThumbnailHelper";
    public static final int THUMBNAIL_SIZE = 300;

    public static String createAndSaveThumbnail(File originalFile, File targetFile) {
        Log.d(TAG, "开始处理: " + originalFile.getAbsolutePath());

        if (!originalFile.exists()) {
            Log.e(TAG, "源文件不存在");
            return null;
        }

        // 1. 使用 ARGB_8888 确保色彩准确，避免 RGB_565 导致的色彩失真
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inJustDecodeBounds = false;

        Bitmap originalBitmap = null;
        Bitmap scaledBitmap = null;

        try {
            // 2. 解码原图
            originalBitmap = BitmapFactory.decodeFile(originalFile.getAbsolutePath(), options);
            if (originalBitmap == null) {
                Log.e(TAG, "BitmapFactory.decodeFile 返回 null，文件可能损坏");
                return null;
            }

            Log.d(TAG, "解码成功: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight());

            // 3. 核心修复：使用 createScaledBitmap 替代 ThumbnailUtils
            // filter = true 表示使用双线性滤波，缩放质量更好，且必定返回一个全新的 Bitmap 对象
            scaledBitmap = Bitmap.createScaledBitmap(
                    originalBitmap,
                    THUMBNAIL_SIZE,
                    THUMBNAIL_SIZE,
                    true
            );

            if (scaledBitmap == null) {
                Log.e(TAG, "生成缩放图失败");
                return null;
            }

            // 4. 原图已经没用了，立刻回收，释放内存
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }

            // 5. 确保目录存在
            if (!targetFile.getParentFile().exists()) {
                targetFile.getParentFile().mkdirs();
            }

            // 6. 保存为 JPEG
            boolean result = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, new FileOutputStream(targetFile));

            if (result) {
                Log.d(TAG, "✅ 缩略图保存成功: " + targetFile.getAbsolutePath());
                return targetFile.getAbsolutePath();
            } else {
                Log.e(TAG, "❌ 缩略图保存失败 (Compress 返回 false)");
                return null;
            }

        } catch (IOException e) {
            Log.e(TAG, "文件 IO 异常", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "未知异常", e);
            return null;
        } finally {
            // 7. 最终兜底：确保缩放后的缩略图被回收（如果前面没有因为异常中断）
            // 注意：originalBitmap 已经在缩放后回收了，这里只回收 scaledBitmap
            if (scaledBitmap != null && !scaledBitmap.isRecycled()) {
                scaledBitmap.recycle();
            }
            // 双重保险，防止 originalBitmap 在异常时没被回收
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }
        }
    }

    /**
     * 将原图的 EXIF 信息（拍摄日期、地点、相机参数等）复制到缩略图中
     */
    public static void copyExifInfo(File sourceFile, File destFile) {
        try {
            // 读取原图的 EXIF 信息
            ExifInterface sourceExif = new ExifInterface(sourceFile.getAbsolutePath());
            // 准备写入缩略图的 EXIF 信息
            ExifInterface destExif = new ExifInterface(destFile.getAbsolutePath());

            // 定义需要保留的关键 EXIF 标签
            String[] tags = {
                    ExifInterface.TAG_DATETIME,           // 拍摄日期和时间
                    ExifInterface.TAG_DATETIME_ORIGINAL,  // 原始拍摄时间
                    ExifInterface.TAG_DATETIME_DIGITIZED, // 数字化时间
                    ExifInterface.TAG_GPS_LATITUDE,       // GPS 纬度
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,      // GPS 经度
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,       // GPS 海拔
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP,      // GPS 时间戳
                    ExifInterface.TAG_GPS_DATESTAMP,      // GPS 日期戳
                    ExifInterface.TAG_MAKE,               // 设备制造商
                    ExifInterface.TAG_MODEL,              // 设备型号
                    ExifInterface.TAG_ORIENTATION         // 图片旋转方向（防止缩略图歪掉）
            };

            // 逐个将原图的属性复制到缩略图中
            for (String tag : tags) {
                String value = sourceExif.getAttribute(tag);
                if (value != null) {
                    destExif.setAttribute(tag, value);
                }
            }

            // 保存写入的 EXIF 信息到缩略图文件
            destExif.saveAttributes();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}