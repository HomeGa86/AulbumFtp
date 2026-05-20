package com.echo2080.picsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Vector;

import static android.content.Context.MODE_PRIVATE;

public class SftpHelper implements FtpInterface {

    private Session session;
    private ChannelSftp channelSftp;
    // 用于将进度回调切换到主线程执行
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LogHelper logHelper;

    /**
     * 连接 SFTP 服务器
     */
    public boolean connect(Context context) {
        if (channelSftp != null && channelSftp.isConnected() && session != null && session.isConnected()) {
            return true;
        }
        return performConnect(context);
    }

    /**
     * 重新连接 SFTP 服务器（断线重连专用）
     */
    public boolean reconnect(Context context) {
        disconnect();
        return performConnect(context);
    }

    /**
     * 实际执行连接逻辑的私有方法
     */
    private boolean performConnect(Context context) {
        logHelper = new LogHelper(context);
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);

        // 读取主服务器配置
        String host = prefs.getString("ftp_host", "");
        int port = Integer.parseInt(prefs.getString("ftp_port", "22"));

        // 读取备用服务器配置
        String backupHost = prefs.getString("backup_ftp_host", "");
        int backupPort = Integer.parseInt(prefs.getString("backup_ftp_port", "22"));

        String user = prefs.getString("ftp_user", "");
        String password = prefs.getString("ftp_pass", "");

        // 1. 优先尝试连接主服务器
        if (connectToServer(host, port, user, password)) {
            return true;
        }

        // 2. 如果主服务器连接失败，且备用服务器地址不为空，则尝试连接备用服务器
        if (!backupHost.isEmpty()) {
            Log.d("SFTP_CONNECT", "主服务器连接失败，正在尝试连接备用服务器: " + backupHost + ":" + backupPort);
            return connectToServer(backupHost, backupPort, user, password);
        }

        // 3. 两个都失败了，返回 false
        return false;
    }

    private boolean connectToServer(String host, int port, String user, String password) {
        try {
            JSch jsch = new JSch();

            // 👇 核心修复：直接在 JSch 实例上全局添加 ssh-ed25519 到支持列表中
            // 这样能确保客户端在握手时一定会把 ed25519 发给服务器
            String currentAlgorithms = JSch.getConfig("server_host_key");
            if (!currentAlgorithms.contains("ssh-ed25519")) {
                JSch.setConfig("server_host_key", "ssh-ed25519," + currentAlgorithms);
            }

            session = jsch.getSession(user, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(30000);
            session.connect();

            Channel channel = session.openChannel("sftp");
            channel.connect();
            channelSftp = (ChannelSftp) channel;

            Log.d("SFTP_CONNECT", "成功连接到 SFTP 服务器: " + host + ":" + port);
            return true;

        } catch (JSchException e) {
            logHelper.logToFile("Failed to connect to " + host + ":" + port + "@" + user);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            disconnect(); // 发生异常也要断开清理
            return false;
        }
    }


    /**
     * 断开 SFTP 连接
     */
    public void disconnect() {
        try {
            if (channelSftp != null && channelSftp.isConnected()) {
                channelSftp.exit();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 递归遍历 SFTP 服务器上的所有文件
     */
    public void listAllFiles(String remotePath, List<String> fileList) throws IOException {
        try {
            Vector<ChannelSftp.LsEntry> entries = channelSftp.ls(remotePath);
            for (ChannelSftp.LsEntry entry : entries) {
                if (".".equals(entry.getFilename()) || "..".equals(entry.getFilename())) {
                    continue;
                }
                String fullPath = remotePath.endsWith("/") ? remotePath + entry.getFilename() : remotePath + "/" + entry.getFilename();
                if (entry.getAttrs().isDir()) {
                    if (!entry.getFilename().startsWith(".")) {
                        listAllFiles(fullPath, fileList);
                    }
                } else {
                    String name = entry.getFilename().toLowerCase();
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                            || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")
                            || name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov")
                            || name.endsWith(".avi") || name.endsWith(".3gp")) {
                        fileList.add(fullPath);
                    }
                }
            }
        } catch (SftpException e) {
            logHelper.logToFile("Failed to list files in " + remotePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            throw new IOException("Failed to list files in " + remotePath, e);
        }
    }

    /**
     * 下载文件（无进度监听的基础版本）
     */
    public boolean downloadFile(String remoteFilePath, File localFile) {
        return downloadFile(remoteFilePath, localFile, null);
    }

    /**
     * 下载文件（带实时进度和速度监听）
     */
    /**
     * 下载文件（带实时进度和速度监听）
     */
    /**
     * 下载文件（带实时进度和速度监听）
     */
    /**
     * 下载文件（带实时进度和速度监听）
     */
    public boolean downloadFile(String remoteFilePath, File localFile, DownloadProgressListener listener) {
        boolean downloadSuccess = false;
        long fileSize = getFileSize(remoteFilePath); // 获取远程文件大小

        try (FileOutputStream fos = new FileOutputStream(localFile)) {
            // 使用 JSch 的 get 方法，传入 SftpProgressMonitor 来监控进度
            channelSftp.get(remoteFilePath, fos, new com.jcraft.jsch.SftpProgressMonitor() {
                long totalBytesRead = 0;
                long lastUpdate = System.currentTimeMillis();
                long startTime = System.currentTimeMillis();

                @Override
                public void init(int op, String src, String dest, long max) {
                    // 传输开始前的初始化
                }

                @Override
                public boolean count(long count) {
                    totalBytesRead += count; // 累加本次传输的字节数
                    long currentTime = System.currentTimeMillis();

                    // 每隔 1000 毫秒（1秒）更新一次 UI
                    if (currentTime - lastUpdate > 1000 && fileSize > 0) {
                        long timeElapsed = (currentTime - startTime) / 1000;
                        if (timeElapsed > 0) {
                            long speedBytesPerSec = totalBytesRead / timeElapsed;
                            String progressPercent = String.format("%.2f%%", (totalBytesRead * 100.0 / fileSize));
                            String speed = formatSize(speedBytesPerSec) + "/s";
                            String statusText = progressPercent + " - " + speed;
                            int intProgress = (int) ((totalBytesRead * 100L) / fileSize);


                            if (listener != null) {
                                mainHandler.post(() -> listener.onProgress(intProgress,statusText));
                            }
                        }
                        lastUpdate = currentTime;
                    }
                    return true; // 返回 true 继续传输，返回 false 则会取消传输
                }

                @Override
                public void end() {
                    // 传输结束时的回调（这里不再依赖外部的 downloadSuccess 变量，避免编译报错）
                }
            });

            downloadSuccess = true;

        } catch (Exception e) {
            logHelper.logToFile("Failed to download file " + remoteFilePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            e.printStackTrace();
            downloadSuccess = false;
        } finally {
            // 💡 修复点：在这里统一进行最终的进度和状态回调
            if (listener != null) {
                final boolean isSuccess = downloadSuccess;
                if (isSuccess) {
                    mainHandler.post(() -> listener.onProgress(100,"100.00% - 完成"));
                }
                mainHandler.post(() -> listener.onFinish(isSuccess));
            }
            // 如果下载失败，删除本地未完成的残次品文件
            if (!downloadSuccess && localFile.exists()) {
                localFile.delete();
            }
        }
        return downloadSuccess;
    }
    /**
     * 辅助方法：将字节大小格式化为人类可读的字符串 (B, KB, MB, GB)
     */
    private String formatSize(long sizeInBytes) {
        if (sizeInBytes < 1024) {
            return sizeInBytes + " B";
        } else if (sizeInBytes < 1024 * 1024) {
            return String.format("%.2f KB", sizeInBytes / 1024.0);
        } else if (sizeInBytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", sizeInBytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", sizeInBytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 上传本地文件到 SFTP 服务器（支持自动创建目录）
     */
    public boolean uploadFile(String remoteFilePath, File localFile) {
        if (localFile == null || !localFile.exists()) {
            Log.d("SftpHelper", "uploadFile not existing:" + localFile.getAbsolutePath());
            logHelper.logToFile("Failed to uploadFile " + localFile.getAbsolutePath() + " to " + remoteFilePath);
            logHelper.logToFile("File not existing:" + remoteFilePath);
            return false;
        }

        try {
            String remoteDirPath = remoteFilePath.substring(0, remoteFilePath.lastIndexOf('/'));
            if (!makeDirectory(remoteDirPath)) {
                Log.d("SftpHelper", "makeDirectory failed");
                return false;
            }

            try (java.io.FileInputStream fis = new java.io.FileInputStream(localFile)) {
                channelSftp.put(fis, remoteFilePath);
                return true;
            }

        } catch (Exception e) {
            logHelper.logToFile("Failed to uploadFile " + localFile.getAbsolutePath() + " to " + remoteFilePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            Log.d("SftpHelper", "upload file failed");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 递归创建远程目录
     */
    private boolean makeDirectory(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) {
            return true;
        }

        try {
            channelSftp.stat(remotePath);
            return true;
        } catch (SftpException e) {
            // stat 失败通常意味着目录不存在
        }

        try {
            int lastSlashIndex = remotePath.lastIndexOf('/');
            if (lastSlashIndex > 0) {
                String parentPath = remotePath.substring(0, lastSlashIndex);
                if (!parentPath.isEmpty() && !parentPath.equals(remotePath)) {
                    if (!makeDirectory(parentPath)) {
                        Log.d("SftpHelper", "make parent path failed:" + parentPath);
                        return false;
                    }
                }
            }
            channelSftp.mkdir(remotePath);
            return true;
        } catch (SftpException e) {
            logHelper.logToFile("Failed to create directory: " + remotePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            Log.d("SftpHelper", "make current path failed:" + remotePath);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 获取文件大小
     */
    public long getFileSize(String remoteFilePath) {
        try {
            SftpATTRS attrs = channelSftp.stat(remoteFilePath);
            if (attrs != null) {
                return attrs.getSize();
            }
            return -1;
        } catch (SftpException e) {
            logHelper.logToFile("Failed to get file size: " + remoteFilePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            e.printStackTrace();
            return -1;
        }
    }
}