package com.echo2080.picsync.Utils;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class LogHelper {

    private static final long MAX_LOG_SIZE = 1024 * 1024;
    private final Context context;
    // 💡 新增：用于同步的锁对象
    private static final Object lock = new Object();

    public LogHelper(Context context) {
        this.context = context;
    }

    public void logToFile(String message) {
        // 💡 使用 synchronized 块包裹核心逻辑，保证线程安全
        synchronized (lock) {
            try {
                File logFile = new File(context.getExternalFilesDir(null), "log.log");

                // 检查并清理过大的日志文件
                checkFileSize(logFile);

                // 创建写入器（追加模式）
                FileOutputStream fos = new FileOutputStream(logFile, true);

                // 💡 优化：使用现代且线程安全的日期格式化方式
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault()));
                String logEntry = timestamp + " | " + message + "\n";

                // 写入并关闭
                fos.write(logEntry.getBytes());
                fos.flush();
                fos.close();

            } catch (Exception e) {
                Log.e("LogHelper", "无法写入日志文件: " + e.getMessage());
            }
        }
    }

    private void checkFileSize(File file) {
        if (file.exists() && file.length() > MAX_LOG_SIZE) {
            try {
                // 清空文件内容
                FileOutputStream fos = new FileOutputStream(file);
                fos.write("".getBytes());
                fos.close();
                Log.d("LogHelper", "日志文件过大，已自动清空");
            } catch (Exception e) {
                Log.e("LogHelper", "清理日志文件失败: " + e.getMessage());
            }
        }
    }

    public String readLogFile(Context context) {
        File logFile = new File(context.getExternalFilesDir(null), "log.log");

        if (!logFile.exists()) {
            return "Log file not existing: " + logFile.getAbsolutePath();
        }

        StringBuilder text = new StringBuilder();
        synchronized (lock) {
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                int maxLines = 500;
                // 💡 使用 LinkedList 作为固定容量的滑动窗口
                java.util.Deque<String> recentLines = new java.util.LinkedList<>();

                while ((line = reader.readLine()) != null) {
                    recentLines.addLast(line);
                    // 如果超过了100行，就把最前面（最早）的那一行删掉
                    if (recentLines.size() > maxLines) {
                        recentLines.removeFirst();
                    }
                }

                // 拼接结果
                for (String l : recentLines) {
                    text.append(l).append("\n");
                }

                if (text.length() == 0) {
                    text.append("📄 日志文件为空");
                }

            } catch (IOException e) {
                Log.e("LogHelper", "读取日志失败", e);
                return "❌ 读取失败: " + e.getMessage();
            }
        }
        return text.toString();
    }

    public String readAllLogFile(Context context) {
        File logFile = new File(context.getExternalFilesDir(null), "log.log");

        if (!logFile.exists()) {
            return "Log file not existing: " + logFile.getAbsolutePath();
        }

        StringBuilder text = new StringBuilder();
        synchronized (lock) {
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                // 💡 使用 LinkedList 作为固定容量的滑动窗口
                java.util.Deque<String> recentLines = new java.util.LinkedList<>();

                while ((line = reader.readLine()) != null) {
                    recentLines.addLast(line);
                }

                // 拼接结果
                for (String l : recentLines) {
                    text.append(l).append("\n");
                }

                if (text.length() == 0) {
                    text.append("📄 日志文件为空");
                }

            } catch (IOException e) {
                Log.e("LogHelper", "读取日志失败", e);
                return "❌ 读取日志失败: " + e.getMessage();
            }
        }
        return text.toString();
    }
}