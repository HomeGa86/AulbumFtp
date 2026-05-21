package com.echo2080.picsync;

import android.content.Context;

import com.jcraft.jsch.SftpException;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface FtpInterface {

    /**
     * 连接服务器 (FTP 或 SFTP)
     */
    boolean connect(Context context);

    /**
     * 重新连接服务器（断线重连专用）
     */
    boolean reconnect(Context context);

    /**
     * 断开服务器连接
     */
    void disconnect();

    /**
     * 递归遍历服务器上的所有文件
     */
    void listAllFiles(String remotePath, List<String> fileList) throws IOException, SftpException;

    /**
     * 下载文件
     */
    boolean downloadFile(String remoteFilePath, File localFile);

    /**
     * 上传本地文件到服务器（支持自动创建目录）
     */
    boolean uploadFile(String remoteFilePath, File localFile);

    /**
     * 获取文件大小
     */
    long getFileSize(String remoteFilePath);

    boolean downloadFile(String remoteFilePath, File localFile, DownloadProgressListener listener);
    }