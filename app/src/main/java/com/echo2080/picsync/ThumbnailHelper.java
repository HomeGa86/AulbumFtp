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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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
    public static ExifInfo copyExifInfo(File sourceFile, File destFile) {
        long captureTime = 0;
        double latitude = 0.0;
        double longitude = 0.0;

        try {
            // 读取原图的 EXIF 信息
            ExifInterface sourceExif = new ExifInterface(sourceFile.getAbsolutePath());
            // 准备写入缩略图的 EXIF 信息
            ExifInterface destExif = new ExifInterface(destFile.getAbsolutePath());

            // 1. 提取并转换拍摄时间
            String dateTime = sourceExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (dateTime == null) {
                dateTime = sourceExif.getAttribute(ExifInterface.TAG_DATETIME);
            }
            if (dateTime != null) {
                try {
                    // EXIF 时间格式通常为 "yyyy:MM:dd HH:mm:ss"
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault());
                    Date date = dateFormat.parse(dateTime);
                    if (date != null) {
                        captureTime = date.getTime();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // 2. 提取并转换 GPS 坐标
            String latRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
            String latValue = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
            String lonRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
            String lonValue = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);

            if (latRef != null && latValue != null && lonRef != null && lonValue != null) {
                latitude = convertGpsToDecimal(latValue, latRef);
                longitude = convertGpsToDecimal(lonValue, lonRef);
            }

            // 3. 复制其他关键 EXIF 标签到缩略图
            String[] tags = {
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_ORIENTATION
            };

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

        // 返回解析好的数据对象
        return new ExifInfo(captureTime, latitude, longitude);
    }

    // 辅助方法：将 EXIF 的度分秒格式 (如 "51/1,2/1,3/1") 转换为十进制数字
    private static double convertGpsToDecimal(String gpsCoordinate, String ref) {
        double decimalDegree = 0;
        try {
            String[] parts = gpsCoordinate.split(",");
            if (parts.length == 3) {
                double degrees = evalFraction(parts[0]);
                double minutes = evalFraction(parts[1]);
                double seconds = evalFraction(parts[2]);
                decimalDegree = degrees + (minutes / 60.0) + (seconds / 3600.0);

                // 如果是南纬(S)或西经(W)，结果需要取负数
                if ("S".equals(ref) || "W".equals(ref)) {
                    decimalDegree = -decimalDegree;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return decimalDegree;
    }

    // 辅助方法：计算分数形式字符串 (如 "2/1") 的值
    private static double evalFraction(String fraction) {
        try {
            String[] nums = fraction.split("/");
            if (nums.length == 2) {
                return Double.parseDouble(nums[0]) / Double.parseDouble(nums[1]);
            }
            return Double.parseDouble(fraction);
        } catch (Exception e) {
            return 0;
        }
    }
    // 用于承载 EXIF 解析结果的内部类
    public static class ExifInfo {
        public long captureTime;      // 拍摄时间戳（毫秒），如果解析失败则为 0
        public double latitude;       // 纬度，如果没有则为 0.0
        public double longitude;      // 经度，如果没有则为 0.0
        public boolean hasLocation;   // 标记是否包含有效的 GPS 信息

        public ExifInfo(long captureTime, double latitude, double longitude) {
            this.captureTime = captureTime;
            this.latitude = latitude;
            this.longitude = longitude;
            this.hasLocation = (latitude != 0.0 || longitude != 0.0);
        }
    }
}