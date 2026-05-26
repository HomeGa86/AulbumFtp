package site.rossiluo.picsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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
        Log.d(TAG, "Connecting to primary server...");
        if (primaryHelper.connect(context)) {
            activeHelper = primaryHelper;
            Log.d(TAG, "Primary server connected");
            return true;
        }

        if (hasBackup && backupHelper != null) {
            Log.d(TAG, "Connecting to backup server...");
            if (backupHelper.connect(context)) {
                activeHelper = backupHelper;
                Log.d(TAG, "Backup server connected");
                return true;
            }
        }

        Log.e(TAG, "Failed to connect to any server");
        activeHelper = null;
        return false;
    }

    @Override
    public boolean reconnect(Context context) {
        if (activeHelper != null) {
            return activeHelper.reconnect(context);
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
    public long getFileSize(String remoteFilePath) {
        checkActive(); return activeHelper.getFileSize(remoteFilePath);
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
}