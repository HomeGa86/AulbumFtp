package site.rossiluo.picsync;

public class PhotoItem {
    public String localPath;      // 本地缩略图路径

    public PhotoItem(String localPath, String ftpPath, long dateTaken) {
        this.localPath = localPath;
        this.ftpPath = ftpPath;
        this.dateTaken = dateTaken;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public void setFtpPath(String ftpPath) {
        this.ftpPath = ftpPath;
    }

    public void setDateTaken(long dateTaken) {
        this.dateTaken = dateTaken;
    }

    public String ftpPath;        // FTP服务器路径

    public String getLocalPath() {
        return localPath;
    }

    public String getFtpPath() {
        return ftpPath;
    }

    public long getDateTaken() {
        return dateTaken;
    }

    public long dateTaken;        // 拍摄时间
    // 构造函数和 Getter/Setter...
}

