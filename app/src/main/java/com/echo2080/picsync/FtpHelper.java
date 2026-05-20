package com.echo2080.picsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static android.content.Context.MODE_PRIVATE;

public class FtpHelper implements FtpInterface {

    private FTPClient ftpClient;
    // 用于将进度回调切换到主线程执行
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context context;
    private LogHelper logHelper;


    /**
     * 连接 FTP 服务器
     */
    public boolean connect(Context context) {
        // 如果已经有连接且是活跃状态，直接返回 true
        if (ftpClient != null && ftpClient.isConnected()) {
            return true;
        }
        return performConnect(context);
    }

    /**
     * 重新连接 FTP 服务器（断线重连专用）
     */
    public boolean reconnect(Context context) {
        // 1. 先强制断开旧的、可能已经失效的连接
        disconnect();
        // 2. 重新发起连接
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
        int port = Integer.parseInt(prefs.getString("ftp_port", "21"));

        String backupHost = prefs.getString("backup_ftp_host", "");
        int backupPort = Integer.parseInt(prefs.getString("backup_ftp_port", "21"));

        String user = prefs.getString("ftp_user", "anonymous");
        String password = prefs.getString("ftp_pass", "");

        // 1. 优先尝试连接主服务器
        if (connectToServer(host, port, user, password)) {
            return true;
        }

        // 2. 如果主服务器连接失败，且备用服务器地址不为空，则尝试连接备用服务器
        if (!backupHost.isEmpty()) {
            Log.d("FTP_CONNECT", "主服务器连接失败，正在尝试连接备用服务器: " + backupHost + ":" + backupPort);
            return connectToServer(backupHost, backupPort, user, password);
        }

        // 3. 两个都失败了，返回 false
        return false;
    }

    private boolean connectToServer(String host, int port, String user, String password) {
        try {
            ftpClient = new FTPClient();

            // 设置连接超时时间（比如10秒），防止卡死太久
            ftpClient.setConnectTimeout(10000);

            ftpClient.connect(host, port);
            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                logHelper.logToFile("Failed to connect to " + host + ":" + port + "@" + user);
                logHelper.logToFile("FTPReply.isPositiveCompletion is false. replyCode is " + replyCode);
                disconnect();
                return false;
            }

            boolean loginSuccess = ftpClient.login(user, password);
            if (!loginSuccess) {
                logHelper.logToFile("Failed to login to " + host + ":" + port + "@" + user + ". Username or Password might be wrong.");
                disconnect();
                return false;
            }

            // 统一配置传输模式
            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setControlKeepAliveTimeout(300); // 5分钟保活

            Log.d("FTP_CONNECT", "成功连接到服务器: " + host + ":" + port);
            return true;

        } catch (IOException e) {
            logHelper.logToFile("Failed to connect to " + host + ":" + port + "@" + user);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            e.printStackTrace();
            disconnect(); // 发生异常也要断开清理
            return false;
        }
    }


    /**
     * 断开 FTP 连接
     */
    public void disconnect() {
        try {
            if (ftpClient != null && ftpClient.isConnected()) {
                ftpClient.logout();
                ftpClient.disconnect();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 递归遍历 FTP 服务器上的所有文件
     */
    public void listAllFiles(String remotePath, List<String> fileList) throws IOException {
        FTPFile[] files = ftpClient.listFiles(remotePath);
        for (FTPFile file : files) {
            String fullPath = remotePath.endsWith("/") ? remotePath + file.getName() : remotePath + "/" + file.getName();
            if (file.isDirectory()) {
                if (!file.getName().startsWith(".")) { // 跳过隐藏目录
                    listAllFiles(fullPath, fileList);
                }
            } else if (file.isFile()) {
                // 只处理图片文件
                String name = file.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp") || name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov")
                        || name.endsWith(".avi") || name.endsWith(".3gp")) {
                    fileList.add(fullPath);
                }
            }
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
    public boolean downloadFile(String remoteFilePath, File localFile, DownloadProgressListener listener) {
        boolean downloadSuccess = false;
        long fileSize = getFileSize(remoteFilePath); // 获取远程文件大小用于计算百分比

        try (FileOutputStream fos = new FileOutputStream(localFile)) {
            // 获取服务器的输入流
            InputStream inputStream = ftpClient.retrieveFileStream(remoteFilePath);
            if (inputStream == null) {
                logHelper.logToFile("Failed to download file " + remoteFilePath + " to " + localFile.getAbsolutePath() + ". inputStream is null.");
                return false;
            }

            byte[] buffer = new byte[8192]; // 8KB 缓冲区
            int bytesRead;
            long totalBytesRead = 0;
            long startTime = System.currentTimeMillis();
            long lastUpdate = startTime;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                // 每隔 1000 毫秒（1秒）更新一次 UI
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUpdate > 1000 && fileSize > 0) {
                    long timeElapsed = (currentTime - startTime) / 1000; // 已过去的秒数
                    if (timeElapsed > 0) {
                        long speedBytesPerSec = totalBytesRead / timeElapsed; // 平均速度

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
            }

            // 必须调用 completePendingCommand 来确认传输彻底完成
            downloadSuccess = ftpClient.completePendingCommand();

            // 确保最后回调一次 100% 的状态
            if (downloadSuccess && listener != null) {
                mainHandler.post(() -> listener.onProgress(100,"100.00% - 完成"));
            }

        } catch (IOException e) {
            logHelper.logToFile("Failed to download file " + remoteFilePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            e.printStackTrace();
            downloadSuccess = false;
        } finally {
            // 💡 修复点：在这里进行 Lambda 引用，此时 downloadSuccess 已经是确定值了
            if (listener != null) {
                final boolean isSuccess = downloadSuccess;
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
     * 上传本地文件到 FTP 服务器（支持自动创建目录）
     */
    public boolean uploadFile(String remoteFilePath, File localFile) {
        if (localFile == null || !localFile.exists()) {
            logHelper.logToFile("Failed to uploadFile " + localFile.getAbsolutePath() + " to " + remoteFilePath);
            logHelper.logToFile("File not existing:" + remoteFilePath);
            Log.d("FtpHelper", "uploadFile not existing:" + localFile.getAbsolutePath());
            return false;
        }

        try {
            String remoteDirPath = remoteFilePath.substring(0, remoteFilePath.lastIndexOf('/'));
            if (!makeDirectory(remoteDirPath)) {
                Log.d("FtpHelper", "makeDirectory failed");
                return false;
            }

            if (!ftpClient.changeWorkingDirectory(remoteDirPath)) {
                Log.d("FtpHelper", "changeWorkingDirectory failed");
                return false;
            }

            try (java.io.FileInputStream fis = new java.io.FileInputStream(localFile)) {
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
                String fileName = remoteFilePath.substring(remoteFilePath.lastIndexOf('/') + 1);
                return ftpClient.storeFile(fileName, fis);
            }

        } catch (IOException e) {
            logHelper.logToFile("Failed to uploadFile " + localFile.getAbsolutePath() + " to " + remoteFilePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            Log.d("FtpHelper", "upload file failed");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 递归创建远程目录
     */
    private boolean makeDirectory(String remotePath) throws IOException {
        if (remotePath == null || remotePath.isEmpty()) {
            return true;
        }

        if (ftpClient.changeWorkingDirectory(remotePath)) {
            return true;
        }

        String parentPath = remotePath.substring(0, remotePath.lastIndexOf('/'));
        if (!parentPath.isEmpty() && !parentPath.equals(remotePath)) {
            if (!makeDirectory(parentPath)) {
                Log.d("FtpHelper", "make parent path failed:" + parentPath);
                return false;
            }
        }

        String dirName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        if (ftpClient.makeDirectory(dirName)) {
            return true;
        } else {
            Log.d("FtpHelper", "make current path failed:" + dirName);
            return ftpClient.changeWorkingDirectory(remotePath);
        }
    }

    /**
     * 获取文件大小
     */
    public long getFileSize(String remoteFilePath) {
        try {
            FTPFile[] files = ftpClient.listFiles(remoteFilePath);
            if (files != null && files.length > 0) {
                return files[0].getSize();
            }
            return -1;
        } catch (IOException e) {
            logHelper.logToFile("Failed to getFileSize " + remoteFilePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            e.printStackTrace();
            return -1;
        }
    }
}