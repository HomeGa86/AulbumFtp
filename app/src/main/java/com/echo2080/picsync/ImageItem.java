package site.rossiluo.picsync;

public class ImageItem {
    public static final int TYPE_HEADER = 0;   // 标题类型
    public static final int TYPE_IMAGE = 1;    // 图片类型

    public int type; // 类型
    public String text; // 用于 Header 显示 "2024年12月"

    private String localUri;
    private String ftpPath;
    public long captureTime; // 【新增】用来存放图片的拍摄时间戳（毫秒）
    private FileType fileType = FileType.PICTURE;

    // 构造方法：用于创建 Header
    public ImageItem(int type, String text) {
        this.type = type;
        this.text = text;
    }

    // 多媒体内容项构造函数
    public ImageItem(String localUri, String ftpPath, long captureTime, FileType fileType) {
        this.type = TYPE_IMAGE;
        this.localUri = localUri;
        this.ftpPath = ftpPath;
        this.captureTime = captureTime;
        this.fileType = fileType;
    }
    public String getLocalUri() {
        return localUri;
    }

    public String getFtpPath() {
        return ftpPath;
    }

    public long getCaptureTime() {
        return captureTime;
    }


    // ⬅️ 新增 setter 方法，用于 LiveData 回调时更新路径
    public void setFtpPath(String ftpPath) {
        this.ftpPath = ftpPath;
    }

    public FileType getFileType() { return fileType; }
}