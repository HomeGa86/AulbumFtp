package com.echo2080.picsync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;

@GlideModule
public class MyAppGlideModule extends AppGlideModule {

    private static final String TAG = "MyAppGlideModule";

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // 设置磁盘缓存大小（50MB）
        int diskCacheSizeBytes = 1024 * 1024 * 50;
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, diskCacheSizeBytes));

        // 设置内存缓存大小
        int memoryCacheSizeBytes = 1024 * 1024 * 20; // 20MB
        builder.setMemoryCache(new LruResourceCache(memoryCacheSizeBytes));

        // 设置位图池大小
        int bitmapPoolSizeBytes = 1024 * 1024 * 10; // 10MB
        builder.setBitmapPool(new LruBitmapPool(bitmapPoolSizeBytes));

        // 设置默认解码格式为高质量
        builder.setDefaultDecodeFormat(DecodeFormat.PREFER_ARGB_8888);

        Log.d(TAG, "Glide configuration applied");
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        // HEIF/HEIC 支持已通过 heif-integration 库自动注册
        Log.d(TAG, "Glide components registered (including HEIC support)");
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
