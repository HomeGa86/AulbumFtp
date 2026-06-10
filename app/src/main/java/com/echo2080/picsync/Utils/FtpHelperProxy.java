package com.echo2080.picsync.Utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.echo2080.picsync.service.DownloadProgressListener;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class FtpHelperProxy implements FtpInterface {
    private static final String TAG = "FtpHelperProxy";
    private final Context context;

    private FtpInterface primaryHelper;
    private FtpInterface backupHelper;
    private FtpInterface activeHelper;
    private boolean hasBackup = false;
    private static volatile String lastSuccessType = null;


    public FtpHelperProxy(Context context) {
        this.context = context.getApplicationContext();
        initHelpers();
    }

    private void initHelpers() {
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);

        // ✅ 读取主服务器配置
        boolean isPrimarySftp = prefs.getBoolean("is_sftp", false);
        String primaryHost = prefs.getString("ftp_host", "");
        int primaryPort = parseIntSafe(
                prefs.getString("ftp_port", isPrimarySftp ? "22" : "21"),
                isPrimarySftp ? 22 : 21
        );
        String primaryUser = prefs.getString("ftp_user", "");
        String primaryPass = prefs.getString("ftp_pass", "");

        // ✅ 通过带参构造函数创建主 Helper
        primaryHelper = isPrimarySftp
                ? new SftpHelper(context, primaryHost, primaryPort, primaryUser, primaryPass)
                : new FtpHelper(context, primaryHost, primaryPort, primaryUser, primaryPass);

        // ✅ 读取备用服务器配置
        String backupHost = prefs.getString("backup_ftp_host", "");
        if (backupHost != null && !backupHost.isEmpty()) {
            hasBackup = true;
            boolean isBackupSftp = prefs.getBoolean("backup_is_sftp", false);
            int backupPort = parseIntSafe(
                    prefs.getString("backup_ftp_port", isBackupSftp ? "22" : "21"),
                    isBackupSftp ? 22 : 21
            );
            // 备用账号密码优先读独立配置，为空则复用主服务器
            String backupUser = prefs.getString("backup_ftp_user", primaryUser);
            String backupPass = prefs.getString("backup_ftp_pass", primaryPass);

            // ✅ 通过带参构造函数创建备用 Helper
            backupHelper = isBackupSftp
                    ? new SftpHelper(context, backupHost, backupPort, backupUser, backupPass)
                    : new FtpHelper(context, backupHost, backupPort, backupUser, backupPass);
        }
    }

    @Override
    public boolean connect(Context context) {
        // 决定首选和备选
        FtpInterface firstChoice;
        FtpInterface secondChoice;
        String firstType;
        String secondType;

        if ("BACKUP".equals(lastSuccessType) && hasBackup && backupHelper != null) {
            // 本次启动后曾成功连过备用服务器 → 优先连备用
            firstChoice = backupHelper;
            firstType = "BACKUP";
            secondChoice = primaryHelper;
            secondType = "PRIMARY";
            Log.d(TAG, "Last success was BACKUP, trying backup first...");
        } else {
            // 默认情况（含首次启动、上次成功的是主服务器、或无备用服务器）→ 优先连主服务器
            firstChoice = primaryHelper;
            firstType = "PRIMARY";
            secondChoice = hasBackup ? backupHelper : null;
            secondType = "BACKUP";
            Log.d(TAG, "Trying primary server first...");
        }

        // 尝试首选服务器
        if (firstChoice.connect(context)) {
            activeHelper = firstChoice;
            lastSuccessType = firstType; // ✅ 记录本次成功类型
            Log.d(TAG, firstType + " server connected");
            return true;
        }

        // 首选失败，尝试备选服务器
        if (secondChoice != null) {
            Log.d(TAG, firstType + " failed, falling back to " + secondType + "...");
            if (secondChoice.connect(context)) {
                activeHelper = secondChoice;
                lastSuccessType = secondType; // ✅ 记录本次成功类型
                Log.d(TAG, secondType + " server connected (fallback)");
                return true;
            }
        }

        Log.e(TAG, "Failed to connect to any server");
        activeHelper = null;
        // ❌ 注意：全部失败时不重置 lastSuccessType
        // 保留上次成功记录，下次重试时仍能优先尝试之前可用的服务器
        return false;
    }


    @Override
    public boolean reconnect(Context context) {
        if (activeHelper != null) {
            boolean success = activeHelper.reconnect(context);
            if (success) {
                lastSuccessType = (activeHelper == backupHelper) ? "BACKUP" : "PRIMARY";
            }
            return success;
        }
        return connect(context);
    }
    @Override
    public void disconnect() {
        if (primaryHelper != null) primaryHelper.disconnect();
        if (backupHelper != null) backupHelper.disconnect();
        activeHelper = null;
    }

    // --- 委托方法（与之前相同）---
    @Override
    public void listAllFiles(String remotePath, List<String> fileList) throws IOException {
        checkActive(); activeHelper.listAllFiles(remotePath, fileList);
    }
    @Override
    public boolean downloadFile(String remoteFilePath, File localFile) {
        checkActive(); return activeHelper.downloadFile(remoteFilePath, localFile);
    }
    @Override
    public boolean downloadFile(String remoteFilePath, File localFile, DownloadProgressListener listener) {
        checkActive(); return activeHelper.downloadFile(remoteFilePath, localFile, listener);
    }
    @Override
    public boolean uploadFile(String remoteFilePath, File localFile) {
        checkActive(); return activeHelper.uploadFile(remoteFilePath, localFile);
    }

    @Override
    public boolean uploadFile(String remoteFilePath, File localFile, DownloadProgressListener listener) {
        checkActive(); return activeHelper.uploadFile(remoteFilePath, localFile, listener);
    }
    @Override
    public long getFileSize(String remoteFilePath) {
        checkActive(); return activeHelper.getFileSize(remoteFilePath);
    }

    @Override
    public boolean deleteFile(String remoteFilePath) {
        checkActive(); return activeHelper.deleteFile(remoteFilePath);
    }

    public FtpInterface getActiveHelper() { return activeHelper; }

    private void checkActive() {
        if (activeHelper == null) {
            throw new IllegalStateException("未连接到任何服务器，请先调用 connect()");
        }
    }

    private int parseIntSafe(String value, int defaultValue) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public static void resetLastSuccessType()
    {
        lastSuccessType = null;
    }
}