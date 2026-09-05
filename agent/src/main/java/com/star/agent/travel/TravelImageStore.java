package com.star.agent.travel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

// 游记图片本地存储（仿 rag_data 模式，运行目录 travel-images/ 目录）：
// 图片存盘 → 返回可访问 URL /travel-images/xxx.jpg（静态资源映射，WebConfig 已注册）
// 单文件 10MB 上限 + 类型白名单（jpg/png/webp/gif）；失败抛 BizException 由全局处理器转话术
@Component
public class TravelImageStore {

    private static final Logger logger = LoggerFactory.getLogger(TravelImageStore.class);

    private static final Path IMAGE_DIR = Paths.get("travel-images");
    private static final long MAX_SIZE = 10 * 1024 * 1024;
    private static final String[] ALLOWED_EXTS = {"jpg", "jpeg", "png", "webp", "gif"};

    // 存图：bytes + 原始文件名 → 返回 URL 路径；校验失败抛异常
    public String save(byte[] bytes, String originalFilename) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        if (bytes.length > MAX_SIZE) {
            throw new IllegalArgumentException("图片超过 10MB 限制");
        }
        String ext = extOf(originalFilename);
        Files.createDirectories(IMAGE_DIR);
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Files.write(IMAGE_DIR.resolve(name), bytes);
        return "/travel-images/" + name;
    }

    // 从 URL 路径取扩展名（白名单校验）
    public static String extOf(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException("缺少文件名");
        }
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("仅支持 jpg/png/webp/gif 图片");
        }
        String ext = lower.substring(dot + 1);
        for (String allowed : ALLOWED_EXTS) {
            if (allowed.equals(ext)) {
                return ext;
            }
        }
        throw new IllegalArgumentException("仅支持 jpg/png/webp/gif 图片");
    }

    // 删除图片文件（游记删除时清理，失败仅日志）
    public void deleteIfExists(String url) {
        if (url == null || !url.startsWith("/travel-images/")) {
            return;
        }
        try {
            String name = url.substring("/travel-images/".length());
            Files.deleteIfExists(IMAGE_DIR.resolve(name));
        } catch (IOException e) {
            logger.warn("游记图片删除失败: {}", e.getMessage());
        }
    }
}