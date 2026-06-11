package com.echo2080.picsync.Utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.util.Log;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import wseemann.media.FFmpegMediaMetadataRetriever;
import android.media.MediaPlayer;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;


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




    public static String createAndSaveThumbnailForVideo(File originalFile, File targetFile, LogHelper logHelper, Context context) {
        // 🎬 视频流基础校验
        if (!originalFile.exists() || originalFile.length() == 0 || !originalFile.canRead()) {
            logHelper.logToFile("Video file not accessible for thumbnail: " + originalFile.getAbsolutePath());
            return null;
        }

        // ==================== 阶段1: 原生 MediaMetadataRetriever ====================
        MediaMetadataRetriever nativeRetriever = new MediaMetadataRetriever();
        boolean nativeReady = false;

        try {
            // 1. 尝试加载原生 MediaMetadataRetriever
            try {
                nativeRetriever.setDataSource(originalFile.getAbsolutePath());
                nativeReady = true;
            } catch (RuntimeException e) {
                logHelper.logToFile("Native retriever setDataSource(path) failed: " + e.getMessage());
                try (FileInputStream fis = new FileInputStream(originalFile)) {
                    nativeRetriever.setDataSource(fis.getFD());
                    nativeReady = true;
                } catch (Exception ex2) {
                    logHelper.logToFile("Native retriever setDataSource(fd) ALSO failed: " + ex2.getMessage());
                }
            }

            // 2. 原生提取逻辑
            if (nativeReady) {
                String width = nativeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height = nativeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

                if (width != null && height != null && Integer.parseInt(width) > 0 && Integer.parseInt(height) > 0) {
                    Bitmap videoFrame = null;
                    long[] candidateTimestamps = {1000000, 0, 2000000, 5000000};
                    for (long t : candidateTimestamps) {
                        videoFrame = nativeRetriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (videoFrame != null) break;
                    }

                    if (videoFrame != null) {
                        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                            Bitmap thumbnail = scaleAndCropToSquare(videoFrame, 300);
                            thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                            // 如果缩放产生了新对象，回收缩略图（videoFrame 在 finally 中回收）
                            if (thumbnail != videoFrame) thumbnail.recycle();
                            logHelper.logToFile("Thumbnail generated successfully via Native MediaMetadataRetriever.");
                            return targetFile.getAbsolutePath();
                        } catch (Exception ex) {
                            logHelper.logToFile("Failed to compress/save native thumbnail: " + ex.getMessage());
                        } finally {
                            videoFrame.recycle();
                        }
                    } else {
                        logHelper.logToFile("Native retriever returned null frame, falling back to FFmpegMediaMetadataRetriever...");
                    }
                } else {
                    logHelper.logToFile("Native retriever failed to get valid width/height, falling back to FFmpegMediaMetadataRetriever...");
                }
            }
        } catch (Exception e) {
            logHelper.logToFile("Unexpected error in native retriever: " + android.util.Log.getStackTraceString(e));
        } finally {
            try {
                nativeRetriever.release();
            } catch (Exception ignored) {}
        }

        // ==================== 阶段2: FFmpegMediaMetadataRetriever 兜底 ====================
        FFmpegMediaMetadataRetriever ffmpegRetriever = new FFmpegMediaMetadataRetriever();
        try {
            logHelper.logToFile("Attempting fallback with FFmpegMediaMetadataRetriever...");
            ffmpegRetriever.setDataSource(originalFile.getAbsolutePath());

            String width = ffmpegRetriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = ffmpegRetriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            if (width != null && height != null && Integer.parseInt(width) > 0 && Integer.parseInt(height) > 0) {
                Bitmap videoFrame = null;
                // 兜底方案使用更激进的时间戳策略，避开微信视频常见的开头黑屏/无效帧区间
                long[] fallbackTimestamps = {2000000, 3000000, 5000000, 10000000, 1000000, 0};
                for (long t : fallbackTimestamps) {
                    videoFrame = ffmpegRetriever.getFrameAtTime(t, FFmpegMediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (videoFrame != null) {
                        logHelper.logToFile("FFmpeg fallback extracted frame at timestamp: " + t + "us");
                        break;
                    }
                }

                if (videoFrame != null) {
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        Bitmap thumbnail = scaleAndCropToSquare(videoFrame, 300);
                        thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                        if (thumbnail != videoFrame) thumbnail.recycle();
                        logHelper.logToFile("Thumbnail generated successfully via FFmpegMediaMetadataRetriever fallback.");
                        return targetFile.getAbsolutePath();
                    } catch (Exception ex) {
                        logHelper.logToFile("Failed to compress/save FFmpeg fallback thumbnail: " + ex.getMessage());
                    } finally {
                        videoFrame.recycle();
                    }
                } else {
                    logHelper.logToFile("FFmpegMediaMetadataRetriever also returned null frame.");
                }
            } else {
                logHelper.logToFile("FFmpegMediaMetadataRetriever failed to get valid width/height.");
            }
        } catch (Exception e) {
            logHelper.logToFile("FFmpegMediaMetadataRetriever fallback failed: " + android.util.Log.getStackTraceString(e));
        } finally {
            try {
                ffmpegRetriever.release();
            } catch (Exception ignored) {}
        }

        return extractFrameViaMediaPlayer(originalFile, targetFile, logHelper);
    }




    /**
     * 【终极兜底】在后台线程使用 MediaPlayer + EGL 离屏渲染提取视频帧
     * ⚠️ 此方法必须在非UI的后台线程中调用（如 ExecutorService / HandlerThread）
     */
    private static String extractFrameViaMediaPlayer(File originalFile, File targetFile, LogHelper logHelper) {
        // EGL 资源
        EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        // Media 资源
        MediaPlayer player = null;
        SurfaceTexture surfaceTexture = null;
        Surface surface = null;
        int[] textures = new int[1];

        try {
            logHelper.logToFile("[L4-Fallback] Starting MediaPlayer + EGL offscreen render...");

            // ==================== 1. 初始化 EGL 离屏渲染环境 ====================
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("Unable to get EGL display");

            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new RuntimeException("Unable to initialize EGL");

            int[] configAttribs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0);

            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("Failed to create EGL context");

            // 创建 1x1 的 Pbuffer 作为占位 Surface（仅用于绑定上下文）
            int[] pbufferAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0);
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new RuntimeException("Failed to make EGL context current");
            }

            // ==================== 2. 创建 GL 外部纹理 & SurfaceTexture ====================
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture = new SurfaceTexture(textures[0]);
            surface = new Surface(surfaceTexture);

            // ==================== 3. MediaPlayer 同步解码 ====================
            player = new MediaPlayer();
            player.setDataSource(originalFile.getAbsolutePath());
            player.setSurface(surface);

            // 同步 prepare（后台线程安全）
            player.prepare();

            int videoWidth = player.getVideoWidth();
            int videoHeight = player.getVideoHeight();
            if (videoWidth <= 0 || videoHeight <= 0) {
                logHelper.logToFile("[L4-Fallback] Invalid video dimensions: " + videoWidth + "x" + videoHeight);
                return null;
            }

            // 更新 SurfaceTexture 缓冲区尺寸
            surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);

            // Seek 到 2秒处避开微信黑屏
            final CountDownLatch seekLatch = new CountDownLatch(1);
            player.setOnSeekCompleteListener(mp -> seekLatch.countDown());
            player.seekTo(2000, MediaPlayer.SEEK_CLOSEST_SYNC);

            if (!seekLatch.await(10, TimeUnit.SECONDS)) {
                logHelper.logToFile("[L4-Fallback] MediaPlayer seek timeout after 10s");
                return null;
            }

            // ⚠️ 关键：seek 完成后等待一小段时间让解码器输出新帧
            SystemClock.sleep(100);

            // 更新纹理（必须在 EGL 上下文绑定的当前线程调用）
            surfaceTexture.updateTexImage();

            // ==================== 4. glReadPixels 读取像素生成 Bitmap ====================
            ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(videoWidth * videoHeight * 4);
            pixelBuffer.order(ByteOrder.nativeOrder());

            GLES20.glViewport(0, 0, videoWidth, videoHeight);
            GLES20.glReadPixels(0, 0, videoWidth, videoHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);

            int glError = GLES20.glGetError();
            if (glError != GLES20.GL_NO_ERROR) {
                logHelper.logToFile("[L4-Fallback] glReadPixels failed with GL error: " + glError);
                return null;
            }

            // OpenGL 读取的像素是 Y轴翻转的，需要手动翻转
            Bitmap rawBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888);
            rawBitmap.copyPixelsFromBuffer(pixelBuffer);

            // 垂直翻转 Bitmap
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.preScale(1f, -1f);
            Bitmap correctedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, videoWidth, videoHeight, matrix, false);
            rawBitmap.recycle();

            // ==================== 5. 保存文件 ====================
            if (correctedBitmap != null) {
                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                    Bitmap thumbnail = scaleAndCropToSquare(correctedBitmap, 300);
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                    if (thumbnail != correctedBitmap) thumbnail.recycle();
                    logHelper.logToFile("[L4-Fallback] Thumbnail generated successfully via MediaPlayer+EGL!");
                    return targetFile.getAbsolutePath();
                } finally {
                    correctedBitmap.recycle();
                }
            }

        } catch (Exception e) {
            logHelper.logToFile("[L4-Fallback] MediaPlayer+EGL fallback failed: " + android.util.Log.getStackTraceString(e));
        } finally {
            // ==================== 6. 严格按逆序释放所有资源 ====================
            try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
            try { if (surface != null) surface.release(); } catch (Exception ignored) {}
            try { if (surfaceTexture != null) surfaceTexture.release(); } catch (Exception ignored) {}
            try {
                if (textures[0] != 0) GLES20.glDeleteTextures(1, textures, 0);
            } catch (Exception ignored) {}
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglTerminate(eglDisplay);
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static Bitmap scaleAndCropToSquare(Bitmap source, int targetSize) {
        if (source == null) return null;

        int srcWidth = source.getWidth();
        int srcHeight = source.getHeight();

        // 如果已经小于等于目标尺寸，无需放大（避免模糊），直接返回原图或按需处理
        // 这里选择：如果原图更小则不放大，保持原样；如需强制300x300可去掉此判断
        if (srcWidth <= targetSize && srcHeight <= targetSize) {
            return source;
        }

        float scale = Math.max(
                (float) targetSize / srcWidth,
                (float) targetSize / srcHeight
        );

        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postScale(scale, scale);

        // 计算居中裁剪的起始坐标
        int scaledWidth = Math.round(srcWidth * scale);
        int scaledHeight = Math.round(srcHeight * scale);
        int startX = (scaledWidth - targetSize) / 2;
        int startY = (scaledHeight - targetSize) / 2;

        Bitmap scaled = Bitmap.createBitmap(source, 0, 0, srcWidth, srcHeight, matrix, true);
        Bitmap cropped = Bitmap.createBitmap(scaled, startX, startY, targetSize, targetSize);

        // 回收中间产物（注意：如果 createBitmap 返回的是同一对象则不能 recycle）
        if (scaled != cropped && !scaled.isRecycled()) {
            scaled.recycle();
        }
        // 注意：不要在这里 recycle source，由调用方负责

        return cropped;
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
    public static long extractTimestampFromFilename(String fileName) {
    if (fileName == null || fileName.isEmpty()) {
        return 0;
    }

    // 0. 先移除文件扩展名，避免干扰
    String nameWithoutExt = fileName.replaceAll("\\.[^.]+$", "");

    // ---------------------------------------------------------
    // 💡 新增优先方案: 纯数字日期格式 (YYYYMMDDHHmmss 或 YYYYMMDDHHmmssSSS)
    // 例如: 20230710062958585 -> 2023-07-10 06:29:58.585
    // 必须在通用方案 A 之前运行，否则 \d{10,13} 会贪婪地匹配错误片段
    // ---------------------------------------------------------
    if (nameWithoutExt.matches("\\d{14,17}")) {
        try {
            int year   = Integer.parseInt(nameWithoutExt.substring(0, 4));
            int month  = Integer.parseInt(nameWithoutExt.substring(4, 6));
            int day    = Integer.parseInt(nameWithoutExt.substring(6, 8));
            int hour   = Integer.parseInt(nameWithoutExt.substring(8, 10));
            int minute = Integer.parseInt(nameWithoutExt.substring(10, 12));
            int second = Integer.parseInt(nameWithoutExt.substring(12, 14));
            int millis = 0;
            if (nameWithoutExt.length() >= 17) {
                millis = Integer.parseInt(nameWithoutExt.substring(14, 17));
            } else if (nameWithoutExt.length() > 14) {
                // 处理15-16位的情况，右对齐补齐到3位
                String msPart = nameWithoutExt.substring(14);
                millis = Integer.parseInt(msPart) * (int) Math.pow(10, 3 - msPart.length());
            }

            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(year, month - 1, day, hour, minute, second);
            calendar.set(java.util.Calendar.MILLISECOND, millis);

            long parsedTime = calendar.getTimeInMillis();
            if (isValidTimestamp(parsedTime)) {
                return parsedTime;
            }
        } catch (Exception e) {
            // 解析失败，继续尝试后续方案
        }
    }

    // ---------------------------------------------------------
    // 优先方案: 针对 mmexport 格式特殊处理
    // 格式: mmexport<32位hex>_<13位时间戳> 或 mmexport<13位时间戳>
    // ---------------------------------------------------------
    if (nameWithoutExt.startsWith("mmexport")) {
        String afterPrefix = nameWithoutExt.substring("mmexport".length());
        
        // 先尝试查找下划线分隔的时间戳
        int lastUnderscore = afterPrefix.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < afterPrefix.length() - 1) {
            String tsStr = afterPrefix.substring(lastUnderscore + 1);
            Long ts = parseAndValidateTimestamp(tsStr);
            if (ts != 0) return ts;
        }
        
        // 如果没有下划线，尝试直接从剩余部分提取时间戳
        // 例如: mmexport1730948290240 -> 1730948290240
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{10,})").matcher(afterPrefix);
        if (m.find()) {
            String digits = m.group(1);
            // 💡 优先尝试 YYYYMMDDHHmmss 日期格式（14+位）
            if (digits.length() >= 14) {
                long dateTs = tryParseYyyyMMddHHmmss(digits);
                if (dateTs != 0) return dateTs;
            }
            Long ts = parseAndValidateTimestamp(digits.length() > 13 ? digits.substring(0, 13) : digits);
            if (ts != 0) return ts;
        }
    }

    // ---------------------------------------------------------
    // 通用方案 A: 按下划线分割，逐段查找有效时间戳
    // 解决 \b 在 hex+数字 交界处失效的问题
    // ---------------------------------------------------------
    String[] parts = nameWithoutExt.split("_");
    for (String part : parts) {
        // 去除可能残留的前缀
        String cleaned = part
                .replace("mmexport", "")
                .replace("IMG", "")
                .replace("img", "")
                .replace("VID", "")
                .replace("WhatsAppImage", "")
                .replace("WhatsAppVideo", "");

        // 从清理后的片段中提取连续数字（10位及以上）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{10,})").matcher(cleaned);
        while (m.find()) {
            String digits = m.group(1);
            // 💡 优先尝试 YYYYMMDDHHmmss 日期格式（14+位）
            // 解决类似 "QQ空间视频_202205110019111652199551655" 的文件名
            if (digits.length() >= 14) {
                long dateTs = tryParseYyyyMMddHHmmss(digits);
                if (dateTs != 0) return dateTs;
            }
            Long ts = parseAndValidateTimestamp(digits.length() > 13 ? digits.substring(0, 13) : digits);
            if (ts != 0) return ts;
        }
    }

    // ---------------------------------------------------------
    // 兜底方案 B: 匹配日期格式 (20260517_123456 等)
    // ---------------------------------------------------------
    String dateRegex = "(\\d{4})[-_]?(\\d{2})[-_]?(\\d{2})[_T ]?(\\d{2}):?(\\d{2}):?(\\d{2})(?:[._](\\d{1,3}))?";
    java.util.regex.Matcher dateMatcher = java.util.regex.Pattern.compile(dateRegex).matcher(nameWithoutExt);

    if (dateMatcher.find()) {
        try {
            int year = Integer.parseInt(dateMatcher.group(1));
            int month = Integer.parseInt(dateMatcher.group(2));
            int day = Integer.parseInt(dateMatcher.group(3));
            int hour = Integer.parseInt(dateMatcher.group(4));
            int minute = Integer.parseInt(dateMatcher.group(5));
            int second = Integer.parseInt(dateMatcher.group(6));

            // 💡 提取可选的毫秒部分
            int millis = 0;
            String msGroup = dateMatcher.group(7);
            if (msGroup != null && !msGroup.isEmpty()) {
                // 补齐到3位: "5" -> 500, "58" -> 580, "585" -> 585
                millis = Integer.parseInt(msGroup) * (int) Math.pow(10, 3 - msGroup.length());
            }

            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(year, month - 1, day, hour, minute, second);
            calendar.set(java.util.Calendar.MILLISECOND, millis);

            long parsedTime = calendar.getTimeInMillis();
            if (isValidTimestamp(parsedTime)) {
                return parsedTime;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

        // ---------------------------------------------------------
        // 兜底方案 C: 仅匹配纯日期格式 (例如 img_2015-10-12_abc.jpg)
        // ---------------------------------------------------------
        String dateOnlyRegex = "(\\d{4})[-_](\\d{2})[-_](\\d{2})";
        java.util.regex.Matcher dateOnlyMatcher = java.util.regex.Pattern.compile(dateOnlyRegex).matcher(nameWithoutExt);

        if (dateOnlyMatcher.find()) {
            try {
                int year = Integer.parseInt(dateOnlyMatcher.group(1));
                int month = Integer.parseInt(dateOnlyMatcher.group(2));
                int day = Integer.parseInt(dateOnlyMatcher.group(3));

                java.util.Calendar calendar = java.util.Calendar.getInstance();
                // 时间默认为当天的 00:00:00
                calendar.set(year, month - 1, day, 0, 0, 0);
                calendar.set(java.util.Calendar.MILLISECOND, 0);

                long parsedTime = calendar.getTimeInMillis();
                if (isValidTimestamp(parsedTime)) {
                    return parsedTime;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        return 0;
}

/**
 * 尝试将字符串的前14位解析为 YYYYMMDDHHmmss 格式的日期时间
 * 带有严格的月/日/时/分/秒范围校验，避免将随机数字误判为日期
 * @return 毫秒时间戳，无效则返回 0
 */
private static long tryParseYyyyMMddHHmmss(String digits) {
    if (digits == null || digits.length() < 14) return 0;
    try {
        int year   = Integer.parseInt(digits.substring(0, 4));
        int month  = Integer.parseInt(digits.substring(4, 6));
        int day    = Integer.parseInt(digits.substring(6, 8));
        int hour   = Integer.parseInt(digits.substring(8, 10));
        int minute = Integer.parseInt(digits.substring(10, 12));
        int second = Integer.parseInt(digits.substring(12, 14));

        if (month < 1 || month > 12) return 0;
        if (day < 1 || day > 31) return 0;
        if (hour > 23 || minute > 59 || second > 59) return 0;

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setLenient(false);
        cal.set(year, month - 1, day, hour, minute, second);
        cal.set(java.util.Calendar.MILLISECOND, 0);

        long ts = cal.getTimeInMillis();
        return isValidTimestamp(ts) ? ts : 0;
    } catch (Exception e) {
        return 0;
    }
}

/**
 * 解析并验证时间戳字符串，返回毫秒时间戳；无效则返回 0
 */
private static Long parseAndValidateTimestamp(String numberStr) {
    try {
        long timestamp;
        if (numberStr.length() == 10) {
            timestamp = Long.parseLong(numberStr) * 1000L;
        } else if (numberStr.length() >= 11 && numberStr.length() <= 13) {
            timestamp = Long.parseLong(numberStr);
        } else {
            return 0L;
        }
        return isValidTimestamp(timestamp) ? timestamp : 0L;
    } catch (NumberFormatException e) {
        return 0L;
    }
}

/**
 * 校验时间戳是否在合理范围内 (2000-01-01 ~ 2030-01-01)
 */
private static boolean isValidTimestamp(long timestamp) {
    final long YEAR_2000 = 946684800000L;
    final long YEAR_2100 = 4102416000000L;
    return timestamp >= YEAR_2000 && timestamp <= YEAR_2100;
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