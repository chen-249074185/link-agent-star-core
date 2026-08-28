package com.star.agent.adapter.web;

import com.star.agent.infra.web.Result;
import com.star.agent.skill.impl.AnyDocService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

// 文件识别接口（Web/微信"上传文件"按钮的后端）：
//   POST /api/file/parse (multipart: userId + file) = 解析文档 → Markdown 文本（txt/md 直读，其余走 anydoc）
// 绑定 userId（JWT 拦截器校验一致，签名同 rag 接口）；失败返回友好话术不崩
@RestController
@RequestMapping("/api/file")
public class FileParseController {

    private final AnyDocService anyDocService;

    public FileParseController(AnyDocService anyDocService) {
        this.anyDocService = anyDocService;
    }

    @PostMapping("/parse")
    public Result<Map<String, Object>> parse(@RequestParam String userId,
                                             @RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();
        String text = anyDocService.parse(file.getBytes(), filename);
        if (text == null) {
            return Result.fail(400, "无法识别该文件（仅支持 pdf/word/excel/ppt/csv/odt/rtf/epub/txt/md）");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filename", filename);
        data.put("size", file.getSize());
        data.put("text", text);
        data.put("chars", text.length());
        return Result.ok(data);
    }
}