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
import java.io.FileInputStream;
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
import java.text.ParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.media.MediaMetadataRetriever;



public class ThumbnailHelper {
    private static final String TAG = "ThumbnailHelper";
    public static final int THUMBNAIL_SIZE = 300;

    public static String createAndSaveThumbnail(File originalFile, File targetFile, LogHelper logHelper) {
        Log.d(TAG, "开始处理: " + originalFile.getAbsolutePath());

        BitmapFactory.Options checkOptions = new BitmapFactory.Options();
        checkOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(originalFile.getAbsolutePath(), checkOptions);

        if (checkOptions.outWidth <= 0 || checkOptions.outHeight <= 0) {
            logHelper.logToFile("Failed to generate thumbnail, width or height is not right:" + originalFile.getAbsolutePath());
            return null;
        }


            if (!originalFile.exists()) {
            Log.e(TAG, "源文件不存在");
            logHelper.logToFile("createAndSaveThumbnail failed, file not existing:" + originalFile.getAbsolutePath());
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
                logHelper.logToFile("createAndSaveThumbnail failed, originalBitmap is null:" + originalFile.getAbsolutePath());
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
                logHelper.logToFile("createAndSaveThumbnail failed, scaledBitmap is null:" + originalFile.getAbsolutePath());
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
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                boolean result = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                if (result) {
                    Log.d(TAG, "✅ 缩略图保存成功: " + targetFile.getAbsolutePath());
                    return targetFile.getAbsolutePath();
                } else {
                    Log.e(TAG, "❌ 缩略图保存失败 (Compress 返回 false)");
                    logHelper.logToFile("createAndSaveThumbnail failed, scaledBitmap.compress failed:" + originalFile.getAbsolutePath());
                    return null;
                }
            } catch (IOException e) {
                throw e;
            }

        } catch (IOException e) {
            Log.e(TAG, "文件 IO 异常", e);
            logHelper.logToFile("createAndSaveThumbnail failed:" + originalFile.getAbsolutePath());
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            return null;
        } catch (Exception e) {
            Log.e(TAG, "未知异常", e);
            logHelper.logToFile("createAndSaveThumbnail failed:" + originalFile.getAbsolutePath());
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
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

    public static String createAndSaveThumbnailForVideo(File originalFile, File targetFile, LogHelper logHelper) {
        // 🎬 视频流处理分支
        if (!originalFile.exists() || originalFile.length() == 0 || !originalFile.canRead()) {
            logHelper.logToFile("Video file not accessible for thumbnail: " + originalFile.getAbsolutePath());
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        boolean retrieverReady = false;
        try {
            try {
                // First try path-based
                retriever.setDataSource(originalFile.getAbsolutePath());
                retrieverReady = true;
            } catch (RuntimeException e) {
                logHelper.logToFile("setDataSource(path) failed for " + originalFile.getAbsolutePath() + ": " + e.getClass().getName() + ": " + e.getMessage());
                // Try fallback with FileDescriptor
                try (FileInputStream fis = new FileInputStream(originalFile)) {
                    retriever.setDataSource(fis.getFD());
                    retrieverReady = true;
                } catch (Exception ex2) {
                    logHelper.logToFile("setDataSource(FileDescriptor) ALSO failed for " + originalFile.getAbsolutePath() + ": " + ex2.getClass().getName() + ": " + ex2.getMessage());
                }
            }

            if (retrieverReady) {
                String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

                if (width != null && height != null && Integer.parseInt(width) > 0 && Integer.parseInt(height) > 0) {
                    // 更健壮的视频帧提取尝试：用多个时间点，直到成功为止
                    Bitmap videoFrame = null;
                    long[] candidateTimestamps = {0, 500_000, 1_000_000, 2_000_000, 5_000_000}; // 单位: 微秒
                    for (long t : candidateTimestamps) {
                        videoFrame = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (videoFrame != null) {
                            break;
                        }
                    }
                    if (videoFrame != null) {
                        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                            videoFrame.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                            return targetFile.getAbsolutePath();
                        } catch (Exception ex) {
                            logHelper.logToFile("Failed to generate thumbnail, videoFrame.compress failed:" + originalFile.getAbsolutePath());
                            logHelper.logToFile(android.util.Log.getStackTraceString(ex));
                        }
                        videoFrame.recycle();
                    } else {
                        logHelper.logToFile("Failed to generate thumbnail, all candidate videoFrame extractions returned null: " + originalFile.getAbsolutePath());
                    }
                } else {
                    logHelper.logToFile("Failed to generate thumbnail or capture time, width or height is not right:" + originalFile.getAbsolutePath());
                }
            } else {
                logHelper.logToFile("Failed to set data source for MediaMetadataRetriever: " + originalFile.getAbsolutePath());
            }
        } catch (Exception e) {
            logHelper.logToFile("Failed to generate thumbnail or capture time:" + originalFile.getAbsolutePath());
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
        } finally {
            try { retriever.release(); } catch (IOException ignored) {
                logHelper.logToFile("Failed to release retriever:" + originalFile.getAbsolutePath());
            }
        }
        return null;
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

            // 1. 提取并转换拍摄时间 (优先使用 EXIF)
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

            // 2. 【新增逻辑】如果 EXIF 中没有时间，则尝试从文件名提取
            if (captureTime == 0) {
                String fileName = sourceFile.getName();
                // 去除扩展名，只保留文件名主体
                int dotIndex = fileName.lastIndexOf('.');
                String nameWithoutExt = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;

                // 尝试提取时间戳 (支持 13位毫秒 和 10位秒)
                captureTime = extractTimestampFromFilename(nameWithoutExt);
            }

            // 3. 提取并转换 GPS 坐标 (保持不变)
            String latRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
            String latValue = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
            String lonRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
            String lonValue = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);

            if (latRef != null && latValue != null && lonRef != null && lonValue != null) {
                latitude = convertGpsToDecimal(latValue, latRef);
                longitude = convertGpsToDecimal(lonValue, lonRef);
            }

            // 4. 复制其他关键 EXIF 标签到缩略图 (保持不变)
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

            // 5. 写入修改后的 EXIF 到目标文件
            destExif.saveAttributes();

        } catch (IOException e) {
            e.printStackTrace();
        }

        // 返回解析好的数据对象
        return new ExifInfo(captureTime, latitude, longitude);
    }


    // ==================================================================================
// 辅助方法：从文件名字符串中提取时间戳 (支持时间戳、日期格式)
// ==================================================================================
    private static long extractTimestampFromFilename(String fileName) {
        // 1. 移除常见的前缀干扰，如 "mmexport", "IMG_", "VID_" 等
        String cleaned = fileName
                .replace("mmexport", "")
                .replace("IMG_", "")
                .replace("img_", "") // 兼容小写 img_
                .replace("VID_", "")
                .replace("WhatsApp_Image", "")
                .replace("WhatsApp_Video", "");

        // ---------------------------------------------------------
        // 方案 A: 尝试匹配纯数字时间戳 (10位秒 或 13位毫秒)
        // 正则：匹配 10位 到 13位 的连续数字
        // ---------------------------------------------------------
        java.util.regex.Pattern timestampPattern = java.util.regex.Pattern.compile("\\b(\\d{10,13})\\b");
        java.util.regex.Matcher timestampMatcher = timestampPattern.matcher(cleaned);

        if (timestampMatcher.find()) {
            String numberStr = timestampMatcher.group(1);
            try {
                long timestamp;
                if (numberStr.length() == 10) {
                    // 如果是10位，视为 Unix 时间戳（秒）
                    timestamp = Long.parseLong(numberStr) * 1000L;
                } else {
                    // 如果是13位，直接解析
                    timestamp = Long.parseLong(numberStr);
                }

                // 安全验证：检查时间戳是否在合理范围内 (2000年 - 2030年)
                long year2000 = 946684800000L;
                long year2030 = 1893456000000L;

                if (timestamp >= year2000 && timestamp <= year2030) {
                    return timestamp;
                }
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }

        // ---------------------------------------------------------
        // 方案 B: 尝试匹配日期格式 (例如: 20260517_123456 或 2026-05-17_123456)
        // 正则解释：
        // (\\d{4})   -> 年份 (4位)
        // [-_]??     -> 可选的分隔符 (- 或 _)
        // (\\d{2})   -> 月份 (2位)
        // [-_]??     -> 可选的分隔符
        // (\\d{2})   -> 日期 (2位)
        // [_T ]??    -> 可选的时间分隔符 (_ 或 T 或 空格)
        // (\\d{2})   -> 小时 (2位)
        // :??        -> 可选的分隔符 (:)
        // (\\d{2})   -> 分钟 (2位)
        // :??        -> 可选的分隔符 (:)
        // (\\d{2})   -> 秒数 (2位)
        // ---------------------------------------------------------
        String dateRegex = "(\\d{4})[-_]? ?(\\d{2})[-_]? ?(\\d{2})[_T ]?(\\d{2}):?(\\d{2}):?(\\d{2})";
        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile(dateRegex);
        java.util.regex.Matcher dateMatcher = datePattern.matcher(cleaned);

        if (dateMatcher.find()) {
            try {
                int year = Integer.parseInt(dateMatcher.group(1));
                int month = Integer.parseInt(dateMatcher.group(2));
                int day = Integer.parseInt(dateMatcher.group(3));
                int hour = Integer.parseInt(dateMatcher.group(4));
                int minute = Integer.parseInt(dateMatcher.group(5));
                int second = Integer.parseInt(dateMatcher.group(6));

                // 使用 Calendar 将提取出的年月日时分秒组装成毫秒时间戳
                java.util.Calendar calendar = java.util.Calendar.getInstance();
                calendar.set(year, month - 1, day, hour, minute, second); // 注意：Calendar的月份是从 0 开始的
                calendar.set(java.util.Calendar.MILLISECOND, 0);

                long parsedTime = calendar.getTimeInMillis();

                // 同样进行安全范围校验 (2000年 - 2030年)
                long year2000 = 946684800000L;
                long year2030 = 1893456000000L;

                if (parsedTime >= year2000 && parsedTime <= year2030) {
                    return parsedTime;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 两种方案均未找到有效时间戳
        return 0;
    }

    /**
     * 尝试从视频元数据或文件名中提取拍摄/录制时间戳
     * @param videoFilePath 本地临时视频文件路径
     * @param fileName 原始文件名（用于正则解析）
     * @return 毫秒级时间戳，如果完全无法获取则返回文件最后的修改时间
     */
    public static long getVideoCaptureTime(String videoFilePath, String fileName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFilePath);
            // 1. 尝试从元数据中获取录制时间 (通常格式为 "20260517T093045.000Z" 或 "Sun May 17 09:30:45 2026")
            String dateString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (dateString != null && !dateString.isEmpty()) {
                Log.d(TAG, "从视频元数据提取到时间字符串: " + dateString);

                // 尝试将其转换为标准的毫秒时间戳
                // 注：因不同设备写入格式不同，这里可以用常见的几种格式尝试解析
                SimpleDateFormat sdfUtc = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault());
                try {
                    // 如果是含有 T 的 ISO 格式
                    if (dateString.contains("T")) {
                        Date date = sdfUtc.parse(dateString.substring(0, 15));
                        if (date != null) return date.getTime();
                    } else {
                        // 如果是其他常见标准格式，直接尝试让 Date 对象解析（处理 Fri May 15 等标准格式）
                        Date date = new Date(dateString);
                        return date.getTime();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "元数据时间字符串解析失败，准备切换到文件名解析: " + dateString);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取视频元数据失败", e);
        } finally {
            try { retriever.release(); } catch (IOException ignored) {}
        }

        // 2. 防御性容错：如果元数据没有，尝试通过正则表达式从文件名中匹配时间（例如 VID_20260517_123456.mp4）
        if (fileName != null) {
            return extractTimestampFromFilename(fileName);
        }

        // 3. 最后的保底方案：返回文件的最后修改时间
        return new File(videoFilePath).lastModified();
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