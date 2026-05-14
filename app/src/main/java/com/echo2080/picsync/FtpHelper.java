package com.echo2080.picsync;

import android.content.Context;
import android.content.SharedPreferences;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import static android.content.Context.MODE_PRIVATE;

public class FtpHelper {

    private FTPClient ftpClient;

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
        ftpClient = new FTPClient();
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);

        // 读取用户保存的配置，如果没有则使用默认值
        String host = prefs.getString("ftp_host", "");
        int port = Integer.parseInt(prefs.getString("ftp_port", "21"));
        String user = prefs.getString("ftp_user", "anonymous");
        String password = prefs.getString("ftp_pass", "");
        String basePath = prefs.getString("ftp_base_path", "/");
        try {
            ftpClient.connect(host, port);
            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                disconnect();
                return false;
            }
            boolean loginSuccess = ftpClient.login(user, password);
            if (!loginSuccess) {
                disconnect();
                return false;
            }
            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setControlKeepAliveTimeout(300); // 5分钟超时
            return true;
        } catch (IOException e) {
            e.printStackTrace();
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
    public void listAllFiles(String remotePath, List<String> fileList) {
        try {
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
                            || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")) {
                        fileList.add(fullPath);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 下载文件
     */
    public boolean downloadFile(String remoteFilePath, File localFile) {
        try (FileOutputStream fos = new FileOutputStream(localFile)) {
            return ftpClient.retrieveFile(remoteFilePath, fos);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
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
            e.printStackTrace();
            return -1;
        }
    }
}