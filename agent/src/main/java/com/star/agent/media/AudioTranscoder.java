package com.star.agent.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// 语音转码器：调 ffmpeg 把任意格式转成 16k 单声道 WAV（ASR 的标准输入格式）
// 降级（R7）：ffmpeg 未安装/转码失败 → 返回 null，调用方走降级话术，不崩
// 设计：ffmpeg 路径可注入（测试传假脚本模拟成功；真实环境默认 "ffmpeg"）
public class AudioTranscoder {

    private final String ffmpegPath;
    private final Path tmpDir;

    public AudioTranscoder(String ffmpegPath) {
        this(ffmpegPath, Path.of(System.getProperty("java.io.tmpdir")));
    }

    AudioTranscoder(String ffmpegPath, Path tmpDir) {
        this.ffmpegPath = ffmpegPath;
        this.tmpDir = tmpDir;
    }

    // 转成 16k 单声道 WAV；ffmpeg 缺失/失败返回 null（降级信号）
    public byte[] to16kWav(byte[] source) {
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile(tmpDir, "voice-in-", ".bin");
            out = Files.createTempFile(tmpDir, "voice-out-", ".wav");
            Files.write(in, source);
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-y", "-i", in.toString(),
                    "-ar", "16000", "-ac", "1", out.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());   // 吞掉 ffmpeg 日志
            int code = p.waitFor();
            if (code != 0 || !Files.exists(out) || Files.size(out) == 0) {
                return null;   // 转码失败 → 降级
            }
            return Files.readAllBytes(out);
        } catch (IOException | InterruptedException e) {
            return null;   // ffmpeg 不存在/被中断 → 降级
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }
}