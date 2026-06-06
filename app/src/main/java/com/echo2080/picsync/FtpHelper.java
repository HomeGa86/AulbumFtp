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
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static android.content.Context.MODE_PRIVATE;

public class FtpHelper implements FtpInterface {

    private FTPClient ftpClient;
    // 用于将进度回调切换到主线程执行
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context context;
    private final String host;
    private final Integer port;
    private final String userName;
    private final String password;
    private LogHelper logHelper;


    public FtpHelper(Context context,String host,Integer port,String userName,String password)
    {
        this.context = context;
        this.host = host;
        this.port = port;
        this.userName = userName;
        this.password = password;
    }

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

        return connectToServer(host, port, userName, password);

    }

    private boolean connectToServer(String host, int port, String user, String password) {
        try {
            if(Thread.currentThread().isInterrupted())
            {
                return false;
            }
            ftpClient = new FTPClient();
            ftpClient.setControlEncoding("UTF-8");

            // 设置连接超时时间（比如10秒），防止卡死太久
            ftpClient.setConnectTimeout(3000);
            ftpClient.setDefaultTimeout(3000);
            ftpClient.setDataTimeout(10000);

            ftpClient.connect(host, port);
            if(Thread.currentThread().isInterrupted())
            {
                return false;
            }
            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                logHelper.logToFile("Failed to connect to " + host + ":" + port + "@" + user);
                logHelper.logToFile("FTPReply.isPositiveCompletion is false. replyCode is " + replyCode);
                disconnect();
                return false;
            }

            boolean loginSuccess = ftpClient.login(user, password);
            if(Thread.currentThread().isInterrupted())
            {
                return false;
            }
            if (!loginSuccess) {
                logHelper.logToFile("Failed to login to " + host + ":" + port + "@" + user + ". Username or Password might be wrong.");
                disconnect();
                return false;
            }

            // 统一配置传输模式
            ftpClient.enterLocalPassiveMode();
            if(Thread.currentThread().isInterrupted())
            {
                return false;
            }
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            if(Thread.currentThread().isInterrupted())
            {
                return false;
            }
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
     * 【对外公开的安全方法】带有自动重试机制的文件列表获取（支持断点续传，绝不回头！）
     */
    public void listAllFiles(String remotePath, List<String> fileList) {
        // 使用 LinkedList 作为任务队列，存放所有等待遍历的目录路径
        LinkedList<String> dirQueue = new LinkedList<>();
        dirQueue.add(remotePath);

        int retryCount = 0;

        while (!dirQueue.isEmpty()) {
            // 💡 关键修改1：先 peek() 而不是 removeFirst()。如果这次处理失败了，这个目录还会继续留在队列头部。
            String currentDir = dirQueue.peek();

            try {
                // 处理当前目录，如果发现子目录，会直接塞回 dirQueue 中等待后续处理
                processDirectory(currentDir, fileList, dirQueue);

                // 💡 关键修改2：只有当 processDirectory 完全成功执行后，才把这个目录从队列里彻底移除！
                dirQueue.removeFirst();

                // 成功处理完一个目录，重置重试计数器
                retryCount = 0;

            } catch (Exception e) {
                retryCount++;
                logHelper.logToFile("列出文件时发生异常，第 " + retryCount + " 次尝试失败: " + e.getMessage());

                if (retryCount >= 5) {
                    logHelper.logToFile("已达到最大重试次数 (" + 5 + ")，放弃列出文件操作。");
                    break;
                }

                logHelper.logToFile("正在尝试重连服务器并进行下一次重试...");
                boolean reconnected = reconnect(context);
                if (!reconnected) {
                    logHelper.logToFile("重连服务器失败！");
                }

                // 等待一段时间再重试
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
                // ⬇️ 此时 currentDir 依然在 dirQueue 的头部，while 循环继续时会重新取出并处理它
            }
        }

        return;
    }

    private void processDirectory(String remotePath, List<String> fileList, Queue<String> dirQueue) throws IOException {
        FTPFile[] files = ftpClient.listFiles(remotePath);

        for (FTPFile file : files) {
            String fullPath = remotePath.endsWith("/") ? remotePath + file.getName() : remotePath + "/" + file.getName();

            if (file.isDirectory()) {
                // 如果是目录且不是隐藏目录，直接丢进队列尾部，等待以后处理
                if (!file.getName().startsWith(".")) {
                    dirQueue.add(fullPath);
                }
            } else if (file.isFile()) {
                // 如果是符合条件的文件，直接加入最终的结果列表
                String name = file.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".heif")
                        || name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov")
                        || name.endsWith(".avi") || name.endsWith(".3gp") || name.endsWith(".hevc") || name.endsWith(".h265")) {
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

            while (!Thread.currentThread().isInterrupted() && (bytesRead = inputStream.read(buffer)) != -1) {
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

            // ✅ 关键：循环退出后，先检查是否因中断而退出
            // 如果是被 cancel(true) 中断的，不要调用 completePendingCommand()
            if (Thread.currentThread().isInterrupted()) {
                android.util.Log.d("FTP_DOWNLOAD", "Download interrupted: " + remoteFilePath);
                downloadSuccess = false;
            } else {
                // 正常传输完毕，确认 FTP 命令完成
                downloadSuccess = ftpClient.completePendingCommand();
                if (downloadSuccess && listener != null) {
                    mainHandler.post(() -> listener.onProgress(100, "100.00%"));
                }
            }
        } catch (IOException e) {
            downloadSuccess = false;
            // ✅ 关键：区分中断异常和真实异常
            // 当 cancel(true) 触发后，try-with-resources 关闭流或 Socket 超时
            // 都可能抛出 IOException，这不是真正的下载失败
            if (Thread.currentThread().isInterrupted()) {
                android.util.Log.d("FTP_DOWNLOAD", "Download cancelled (IOException): " + remoteFilePath);
            } else {
                logHelper.logToFile("Failed to download file " + remoteFilePath);
                logHelper.logToFile(android.util.Log.getStackTraceString(e));
                e.printStackTrace();
            }
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
        return uploadFile(remoteFilePath, localFile, null);
    }

    /**
     * 上传本地文件到 FTP 服务器（带进度监听）
     */
    public boolean uploadFile(String remoteFilePath, File localFile, DownloadProgressListener listener) {
        if (localFile == null || !localFile.exists()) {
            logHelper.logToFile("Failed to uploadFile " + localFile.getAbsolutePath() + " to " + remoteFilePath);
            logHelper.logToFile("File not existing:" + remoteFilePath);
            Log.d("FtpHelper", "uploadFile not existing:" + localFile.getAbsolutePath());
            return false;
        }

        boolean uploadSuccess = false;
        final long fileSize = localFile.length();

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

                // 使用 OutputStream 手动上传以支持进度监听
                try (java.io.OutputStream outputStream = ftpClient.storeFileStream(fileName)) {
                    if (outputStream == null) {
                        logHelper.logToFile("Failed to get output stream for: " + remoteFilePath);
                        return false;
                    }

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytesRead = 0;
                    long startTime = System.currentTimeMillis();
                    long lastUpdate = startTime;

                    while (!Thread.currentThread().isInterrupted() && (bytesRead = fis.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;

                        // 每隔 1000 毫秒（1秒）更新一次 UI
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdate > 1000 && fileSize > 0) {
                            long timeElapsed = (currentTime - startTime) / 1000;
                            if (timeElapsed > 0) {
                                long speedBytesPerSec = totalBytesRead / timeElapsed;
                                String progressPercent = String.format("%.2f%%", (totalBytesRead * 100.0 / fileSize));
                                String speed = formatSize(speedBytesPerSec) + "/s";
                                String statusText = progressPercent + " - " + speed;
                                int intProgress = (int) ((totalBytesRead * 100L) / fileSize);

                                if (listener != null) {
                                    mainHandler.post(() -> listener.onProgress(intProgress, statusText));
                                }
                            }
                            lastUpdate = currentTime;
                        }
                    }

                    outputStream.flush();
                    uploadSuccess = ftpClient.completePendingCommand();

                    if (uploadSuccess && listener != null) {
                        mainHandler.post(() -> listener.onProgress(100, "100.00%"));
                    }
                }
            }

        } catch (IOException e) {
            logHelper.logToFile("Failed to uploadFile " + localFile.getAbsolutePath() + " to " + remoteFilePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            Log.d("FtpHelper", "upload file failed");
            e.printStackTrace();
        } finally {
            if (listener != null) {
                final boolean isSuccess = uploadSuccess;
                mainHandler.post(() -> listener.onFinish(isSuccess));
            }
        }

        return uploadSuccess;
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

    /**
     * 删除服务器上的文件
     */
    public boolean deleteFile(String remoteFilePath) {
        try {
            if (ftpClient == null || !ftpClient.isConnected()) {
                Log.e("FtpHelper", "FTP client not connected");
                return false;
            }
            
            boolean deleted = ftpClient.deleteFile(remoteFilePath);
            if (deleted) {
                Log.d("FtpHelper", "Successfully deleted file: " + remoteFilePath);
                logHelper.logToFile("Successfully deleted file from server: " + remoteFilePath);
            } else {
                Log.e("FtpHelper", "Failed to delete file: " + remoteFilePath);
                logHelper.logToFile("Failed to delete file from server: " + remoteFilePath);
            }
            return deleted;
        } catch (IOException e) {
            Log.e("FtpHelper", "Exception while deleting file: " + remoteFilePath);
            logHelper.logToFile("Exception while deleting file: " + remoteFilePath + "\n" + android.util.Log.getStackTraceString(e));
            e.printStackTrace();
            return false;
        }
    }
}