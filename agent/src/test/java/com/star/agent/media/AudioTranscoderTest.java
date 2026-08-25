package com.star.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AudioTranscoder 单测：不依赖真实 ffmpeg——成功路径用假脚本模拟（复制输入当输出），
// 缺失路径传不存在的路径验证降级返回 null
class AudioTranscoderTest {

    @TempDir
    Path tmpDir;

    // 假 ffmpeg：批处理把输入文件复制成输出文件（模拟"转码成功"）；参数位置固定同真实命令
    // 真实命令：ffmpeg -y -i <in> -ar 16000 -ac 1 <out>  → 输入是第 3 个参数，输出是第 8 个
    private Path fakeFfmpeg() throws Exception {
        Path bat = tmpDir.resolve("fake-ffmpeg.bat");
        Files.writeString(bat, "@echo off\r\ncopy /y \"%~3\" \"%~8\" >nul\r\n");
        return bat;
    }

    @Test
    void transcode_success_withFakeFfmpeg() throws Exception {
        AudioTranscoder transcoder = new AudioTranscoder(fakeFfmpeg().toString(), tmpDir);
        byte[] source = "fake voice bytes".getBytes(StandardCharsets.UTF_8);

        byte[] wav = transcoder.to16kWav(source);

        assertNotNull(wav, "假 ffmpeg 成功应返回字节");
        assertArrayEquals(source, wav, "假转码=原样复制");
    }

    @Test
    void transcode_missingFfmpeg_returnsNull() {
        AudioTranscoder transcoder = new AudioTranscoder(tmpDir.resolve("no-such-ffmpeg.exe").toString(), tmpDir);

        byte[] wav = transcoder.to16kWav(new byte[]{1, 2, 3});

        assertNull(wav, "ffmpeg 缺失应降级返回 null（R7）");
    }

    @Test
    void transcode_failingFfmpeg_returnsNull() throws Exception {
        // 假 ffmpeg 输出失败码 1：验证"转码失败"同样降级
        Path bat = tmpDir.resolve("fail-ffmpeg.bat");
        Files.writeString(bat, "@echo off\r\nexit /b 1\r\n");
        AudioTranscoder transcoder = new AudioTranscoder(bat.toString(), tmpDir);

        assertNull(transcoder.to16kWav(new byte[]{1, 2, 3}));
    }

    @Test
    void transcode_tempFilesCleaned() throws Exception {
        AudioTranscoder transcoder = new AudioTranscoder(fakeFfmpeg().toString(), tmpDir);
        transcoder.to16kWav("data".getBytes(StandardCharsets.UTF_8));

        try (var stream = Files.list(tmpDir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith("voice-")),
                    "临时文件应清理干净");
        }
    }
}