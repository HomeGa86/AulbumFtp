package com.echo2080.picsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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
                        || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")) {
                    fileList.add(fullPath);
                }
            }
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
     * 上传本地文件到 FTP 服务器（支持自动创建目录）
     *
     * @param remoteFilePath FTP 服务器上的目标保存路径（包含文件名，例如：/uploads/2026/05/pic.jpg）
     * @param localFile      本地要上传的文件对象
     * @return 是否上传成功
     */
    public boolean uploadFile(String remoteFilePath, File localFile) {
        if (localFile == null || !localFile.exists()) {
            Log.d("FtpHelper", "uploadFile not existing:" + localFile.getAbsolutePath());
            return false;
        }

        try {
            // 1. 获取文件所在的远程目录路径
            // remoteFilePath 格式如: /dir1/dir2/filename.jpg
            String remoteDirPath = remoteFilePath.substring(0, remoteFilePath.lastIndexOf('/'));

            // 2. 创建远程目录（如果不存在）
            if (!makeDirectory(remoteDirPath)) {
                Log.d("FtpHelper", "makeDirectory failed");
                return false; // 创建目录失败
            }

            // 3. 切换工作目录到目标目录
            if (!ftpClient.changeWorkingDirectory(remoteDirPath)) {
                Log.d("FtpHelper", "changeWorkingDirectory failed");
                return false;
            }

            // 4. 执行上传
            try (java.io.FileInputStream fis = new java.io.FileInputStream(localFile)) {
                // 设置文件类型（防止乱码或损坏）
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
                // 只传文件名，因为工作目录已经切换过去了
                String fileName = remoteFilePath.substring(remoteFilePath.lastIndexOf('/') + 1);
                return ftpClient.storeFile(fileName, fis);
            }

        } catch (IOException e) {
            Log.d("FtpHelper", "upload file failed");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 递归创建远程目录
     *
     * @param remotePath 远程目录路径，例如 /a/b/c
     * @return 是否创建成功（或目录已存在）
     */
    private boolean makeDirectory(String remotePath) throws IOException {
        if (remotePath == null || remotePath.isEmpty()) {
            return true;
        }

        // 尝试切换到该目录，如果成功说明目录已存在
        if (ftpClient.changeWorkingDirectory(remotePath)) {
            // 切换回根目录或其他基准目录，以免影响后续操作（可选，视具体逻辑而定）
            // ftpClient.changeWorkingDirectory("/");
            return true;
        }

        // 如果目录不存在，尝试创建
        // 递归逻辑：先创建父目录，再创建当前目录
        String parentPath = remotePath.substring(0, remotePath.lastIndexOf('/'));
        if (!parentPath.isEmpty() && !parentPath.equals(remotePath)) {
            if (!makeDirectory(parentPath)) {
                Log.d("FtpHelper", "make parent path failed:" + parentPath);
                return false;
            }
        }

        // 创建当前目录
        String dirName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        if (ftpClient.makeDirectory(dirName)) {
            return true;
        } else {
            // 再次检查是否创建成功（防止并发情况下其他线程已创建）
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
            e.printStackTrace();
            return -1;
        }
    }
}