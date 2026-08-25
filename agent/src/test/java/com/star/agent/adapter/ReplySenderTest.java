package com.star.agent.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// ReplySender 单测：优先级选择（语音>图片>地图>文本）+ URL 下载失败降级 + 空回复安全
class ReplySenderTest {

    private MessageAdapter adapter;
    private ImageFetcher fetcher;
    private ReplySender sender;

    @BeforeEach
    void setUp() {
        adapter = mock(MessageAdapter.class);
        fetcher = mock(ImageFetcher.class);
        sender = new ReplySender(fetcher);
    }

    @Test
    void voice_highestPriority() throws Exception {
        byte[] voice = new byte[]{1};
        sender.send("u1", new Reply("文字", "http://img/1.png", null, voice), adapter);

        verify(adapter).sendVoice("u1", voice, "voice.wav");
        verify(adapter, never()).sendImage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(adapter, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void image_aboveText() throws Exception {
        when(fetcher.fetch("http://img/1.png")).thenReturn(new byte[]{2, 2});
        sender.send("u1", new Reply("文字", "http://img/1.png", null, null), adapter);

        verify(adapter).sendImage("u1", new byte[]{2, 2}, "image.png", "");
        verify(adapter, never()).send("u1", "文字");
    }

    @Test
    void map_aboveText() throws Exception {
        when(fetcher.fetch("http://map/1.png")).thenReturn(new byte[]{3, 3});
        sender.send("u1", new Reply("文字", null, "http://map/1.png", null), adapter);

        verify(adapter).sendImage("u1", new byte[]{3, 3}, "image.png", "");
    }

    @Test
    void imageDownloadFailure_degradesToText() throws Exception {
        when(fetcher.fetch("http://img/1.png")).thenThrow(new RuntimeException("下载失败"));

        sender.send("u1", new Reply("地图暂时打不开", "http://img/1.png", null, null), adapter);

        verify(adapter, never()).sendImage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(adapter).send("u1", "地图暂时打不开");
    }

    @Test
    void imageDownloadFailure_emptyText_sendsNothing() throws Exception {
        when(fetcher.fetch("http://img/1.png")).thenThrow(new RuntimeException("下载失败"));

        sender.send("u1", new Reply("", "http://img/1.png", null, null), adapter);

        verify(adapter, never()).sendImage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(adapter, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void textOnly_sendsText() throws Exception {
        sender.send("u1", Reply.text("你好"), adapter);

        verify(adapter).send("u1", "你好");
    }

    @Test
    void allEmpty_sendsNothing() throws Exception {
        sender.send("u1", new Reply(null, null, null, null), adapter);

        verify(adapter, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(adapter, never()).sendImage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(adapter, never()).sendVoice(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}