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
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Vector;

import static android.content.Context.MODE_PRIVATE;

public class SftpHelper implements FtpInterface {

    private final Context context;
    private final String host;
    private final Integer port;
    private final String userName;
    private final String password;
    private Session session;
    private ChannelSftp channelSftp;
    // 用于将进度回调切换到主线程执行
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LogHelper logHelper;

    static {
        try {
            String currentAlgorithms = JSch.getConfig("server_host_key");
            if (currentAlgorithms != null && !currentAlgorithms.contains("ssh-ed25519")) {
                JSch.setConfig("server_host_key", "ssh-ed25519," + currentAlgorithms);
            }
        } catch (Exception e) {
            // 防止获取配置失败导致类加载崩溃
            e.printStackTrace();
        }
    }

    public SftpHelper(Context context,String host,Integer port,String userName,String password) {
        this.context = context;
        this.host = host;
        this.port = port;
        this.userName = userName;
        this.password = password;
    }


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
        return connectToServer(host, port, userName, password);
    }


    private boolean connectToServer(String host, int port, String user, String password) {
        try {
            JSch jsch = new JSch();

            session = jsch.getSession(user, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(10000);
            if(Thread.currentThread().isInterrupted())
            {
                return false;
            }
            session.connect();

            Channel channel = session.openChannel("sftp");
            if(Thread.currentThread().isInterrupted())
            {
                return false;
            }
            channel.connect(10000);
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

    public void listAllFiles(String remotePath, List<String> fileList) {
        // 使用 LinkedList 作为任务队列
        LinkedList<String> dirQueue = new LinkedList<>();
        dirQueue.add(remotePath);

        int retryCount = 0;

        while (!dirQueue.isEmpty()) {
            // 💡 关键修改1：先 peek() 而不是 removeFirst()。
            // 如果这次处理失败了，这个目录还会继续留在队列头部，下次循环还能取到它。
            String currentDir = dirQueue.peek();

            try {
                // 处理当前目录
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


    private void processDirectory(String remotePath, List<String> fileList, Queue<String> dirQueue) throws SftpException {
        Vector<ChannelSftp.LsEntry> entries = channelSftp.ls(remotePath);

        for (ChannelSftp.LsEntry entry : entries) {
            if (".".equals(entry.getFilename()) || "..".equals(entry.getFilename())) {
                continue;
            }

            String fullPath = remotePath.endsWith("/") ? remotePath + entry.getFilename() : remotePath + "/" + entry.getFilename();

            if (entry.getAttrs().isDir()) {
                if (!entry.getFilename().startsWith(".")) {
                    dirQueue.add(fullPath); // 子目录加入队列尾部等待处理
                }
            } else {
                String name = entry.getFilename().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")
                        || name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov")
                        || name.endsWith(".avi") || name.endsWith(".3gp")) {
                    fileList.add(fullPath); // 符合条件的文件加入结果集
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
                    if (Thread.currentThread().isInterrupted()) {
                        return false;
                    }
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

            if (Thread.currentThread().isInterrupted()) {
                downloadSuccess = false;
            } else {
                downloadSuccess = true;
            }

        } catch (Exception e) {
            downloadSuccess = false;
            if (Thread.currentThread().isInterrupted()) {
                // 被 cancel(true) 触发的中断，JSch 会抛 SftpException 或 IOException
                // 这是预期的取消行为，不是错误
                android.util.Log.d("FTP_DOWNLOAD", "Download cancelled: " + remoteFilePath);
            } else {
                logHelper.logToFile("Failed to download file " + remoteFilePath);
                logHelper.logToFile(android.util.Log.getStackTraceString(e));
                e.printStackTrace();
            }
        } finally {
            // 💡 修复点：在这里统一进行最终的进度和状态回调
            if (listener != null) {
                final boolean isSuccess = downloadSuccess;
                if (isSuccess) {
                    mainHandler.post(() -> listener.onProgress(100,"100.00%"));
                }
                mainHandler.post(() -> listener.onFinish(isSuccess));
            }
        }
        return downloadSuccess;
    }

    public boolean downloadFileWithResume(String remoteFilePath, File localFile, long startOffset, DownloadProgressListener listener) {
        boolean downloadSuccess = false;
        long fileSize = getFileSize(remoteFilePath);

        try {
            // 💡 FIX: Pass localFile.getAbsolutePath() instead of an OutputStream.
            // JSch will automatically open the file in append mode when ChannelSftp.RESUME is active.
            channelSftp.get(remoteFilePath, localFile.getAbsolutePath(), new com.jcraft.jsch.SftpProgressMonitor() {
                long totalBytesRead = 0;
                long bytesTransferredThisSession = 0; // 💡 Tracks only network traffic since resume
                long lastUpdate = System.currentTimeMillis();
                long startTime = System.currentTimeMillis();

                @Override
                public void init(int op, String src, String dest, long max) {
                    // JSch tells us 'max' (remaining bytes).
                    // So, the bytes we already have = total - remaining.
                    this.totalBytesRead = fileSize - max;
                    this.bytesTransferredThisSession = 0;
                    this.startTime = System.currentTimeMillis();
                    this.lastUpdate = System.currentTimeMillis();
                }

                @Override
                public boolean count(long count) {
                    totalBytesRead += count;
                    bytesTransferredThisSession += count; // Increment network bytes
                    long currentTime = System.currentTimeMillis();

                    if (currentTime - lastUpdate > 1000 && fileSize > 0) {
                        long timeElapsed = (currentTime - startTime) / 1000;
                        if (timeElapsed <= 0) timeElapsed = 1;

                        // Current speed is strictly based on actual network throughput since the resume
                        long speedBytesPerSec = bytesTransferredThisSession / timeElapsed;

                        String progressPercent = String.format("%.2f%%", (totalBytesRead * 100.0 / fileSize));
                        String speed = formatSize(speedBytesPerSec) + "/s";
                        String statusText = progressPercent + " - " + speed;
                        int intProgress = (int) ((totalBytesRead * 100L) / fileSize);

                        if (listener != null) {
                            mainHandler.post(() -> listener.onProgress(intProgress, statusText));
                        }
                        lastUpdate = currentTime;
                    }
                    return true;
                }

                @Override
                public void end() {}
            }, ChannelSftp.RESUME);

            downloadSuccess = true;
        } catch (Exception e) {
            logHelper.logToFile("Failed to download file with resume: " + remoteFilePath);
            logHelper.logToFile(android.util.Log.getStackTraceString(e));
            e.printStackTrace();
            downloadSuccess = false;
        } finally {
            if (listener != null) {
                final boolean isSuccess = downloadSuccess;
                if (isSuccess) {
                    mainHandler.post(() -> listener.onProgress(100, "100.00%"));
                }
                mainHandler.post(() -> listener.onFinish(isSuccess));
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

    /**
     * 删除服务器上的文件
     */
    public boolean deleteFile(String remoteFilePath) {
        try {
            if (channelSftp == null || !channelSftp.isConnected()) {
                Log.e("SftpHelper", "SFTP client not connected");
                return false;
            }
            
            channelSftp.rm(remoteFilePath);
            Log.d("SftpHelper", "Successfully deleted file: " + remoteFilePath);
            logHelper.logToFile("Successfully deleted file from server: " + remoteFilePath);
            return true;
        } catch (SftpException e) {
            Log.e("SftpHelper", "Exception while deleting file: " + remoteFilePath);
            logHelper.logToFile("Exception while deleting file: " + remoteFilePath + "\n" + android.util.Log.getStackTraceString(e));
            e.printStackTrace();
            return false;
        }
    }
}