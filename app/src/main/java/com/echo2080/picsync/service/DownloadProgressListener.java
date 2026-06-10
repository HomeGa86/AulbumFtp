package com.echo2080.picsync.service;

public interface DownloadProgressListener {
    void onProgress(int intProgress,String progressText); // 用于更新进度文字（如：50% - 2.5 MB/s）
    void onFinish(boolean success);       // 用于下载结束时隐藏进度条
}
