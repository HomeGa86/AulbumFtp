package com.echo2080.picsync.model;

import java.util.*;

public class FileSuffixNames {
    // 图片文件后缀名列表
    private static final List<String> IMAGE_EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".heif"
    ));

    // 视频文件后缀名列表
    private static final List<String> VIDEO_EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
            ".mp4", ".mkv", ".mov", ".avi", ".3gp", ".hevc", ".h265", ".webm", ".flv",".wmv",".mpeg",".mpg",".ts"
    ));

    // 内部合并为一个 Set，提升 contains 判断性能
    private static final Set<String> ALL_EXTENSIONS_SET;
    static {
        Set<String> set = new HashSet<>();
        set.addAll(IMAGE_EXTENSIONS);
        set.addAll(VIDEO_EXTENSIONS);
        ALL_EXTENSIONS_SET = Collections.unmodifiableSet(set);
    }

    /**
     * 判断文件名是否属于支持的图片或视频后缀
     */
    public static boolean isSupportedFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String lowerCaseName = fileName.toLowerCase();
        return ALL_EXTENSIONS_SET.stream().anyMatch(lowerCaseName::endsWith);
    }

    public static boolean isVideo(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        String lowerName = fileName.toLowerCase();
        return VIDEO_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    }

    /**
     * 判断是否为图片文件
     */
    public static boolean isImage(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        String lowerName = fileName.toLowerCase();
        return IMAGE_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    }


}
