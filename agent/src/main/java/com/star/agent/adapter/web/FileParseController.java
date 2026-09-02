package com.star.agent.adapter.web;

import com.star.agent.infra.web.Result;
import com.star.agent.skill.impl.PoiDocParser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 文件识别接口（Web/微信"上传文件"按钮的后端）：
//   POST /api/file/parse (multipart: userId + file) = 解析文档 → 纯文本
// txt/md 直读，docx/xlsx/pptx/pdf/csv 走 PoiDocParser（纯 Java，不依赖 anydoc 子进程）
// 绑定 userId（JWT 拦截器校验一致，签名同 rag 接口）；失败返回友好话术不崩
@RestController
@RequestMapping("/api/file")
public class FileParseController {

    // 直读格式：不劳烦解析器
    private static final List<String> PLAIN_EXTS = List.of("txt", "md", "markdown");

    private final PoiDocParser poiDocParser;

    public FileParseController(PoiDocParser poiDocParser) {
        this.poiDocParser = poiDocParser;
    }

    @PostMapping("/parse")
    public Result<Map<String, Object>> parse(@RequestParam String userId,
                                             @RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();
        byte[] bytes = file.getBytes();
        String ext = PoiDocParser.extOf(filename);
        String text = null;
        if (PLAIN_EXTS.contains(ext)) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else if (PoiDocParser.supports(ext)) {
            text = poiDocParser.parse(bytes, ext);
        }
        if (text == null) {
            return Result.fail(400, "无法识别该文件（仅支持 pdf/txt/md/docx/xlsx/pptx/csv）");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filename", filename);
        data.put("size", file.getSize());
        data.put("text", text);
        data.put("chars", text.length());
        return Result.ok(data);
    }
}