package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import com.star.agent.message.MessageSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// ILinkMessageAdapter 单测：mock ILinkClient（SDK 类），验证收方向缓冲/发方向直通/绑定
class ILinkMessageAdapterTest {

    private ILinkClient client = mock(ILinkClient.class);
    private ILinkMessageAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ILinkMessageAdapter();
    }

    // 造一条微信文本消息（普通 JavaBean，setter 注入）
    private WeixinMessage weixinMsg(String userId, String text) {
        WeixinMessage msg = new WeixinMessage();
        msg.setFrom_user_id(userId);
        MessageItem item = new MessageItem();
        TextItem ti = new TextItem();
        ti.setText(text);
        item.setText_item(ti);
        msg.setItem_list(List.of(item));
        return msg;
    }

    // 未绑定 client 就 receive → 明确报错（防空指针误导）
    @Test
    void receive_withoutAttach_throws() {
        assertThrows(IllegalStateException.class, () -> adapter.receive());
    }

    // receive：单条消息翻译成内部 AgentMessage
    @Test
    void receive_singleMessage_converts() throws Exception {
        adapter.attach(client);
        when(client.getUpdates()).thenReturn(List.of(weixinMsg("u1", "你好")));

        AgentMessage am = adapter.receive();

        assertEquals(MessageSource.WECHAT, am.source());
        assertEquals("u1", am.userId());
        assertEquals("你好", am.text());
    }

    // receive：一次带回一批消息 → pending 缓冲逐条吐，不丢消息
    @Test
    void receive_batchMessages_buffered() throws Exception {
        adapter.attach(client);
        when(client.getUpdates()).thenReturn(List.of(
                weixinMsg("u1", "第一条"),
                weixinMsg("u2", "第二条")
        ));

        assertEquals("第一条", adapter.receive().text());
        assertEquals("第二条", adapter.receive().text());
        verify(client).getUpdates();   // 只拉一次网络，两条都取到
    }

    // receive：通道无消息返回 null（调用方自行决定轮询间隔）
    @Test
    void receive_noMessage_returnsNull() throws Exception {
        adapter.attach(client);
        when(client.getUpdates()).thenReturn(List.of());

        assertNull(adapter.receive());
    }

    // send：直通 SDK sendText
    @Test
    void send_delegatesToClient() throws Exception {
        adapter.attach(client);

        adapter.send("u1", "回复内容");

        verify(client).sendText("u1", "回复内容");
    }

    // sendImage/sendFile：直通 SDK 同签名方法
    @Test
    void sendImageAndFile_delegateToClient() throws Exception {
        adapter.attach(client);
        byte[] img = new byte[]{1, 2, 3};
        byte[] file = new byte[]{4, 5, 6};

        adapter.sendImage("u1", img, "a.png", "看图");
        adapter.sendFile("u1", file, "a.pdf", "文档");

        verify(client).sendImage("u1", img, "a.png", "看图");
        verify(client).sendFile("u1", file, "a.pdf", "文档");
    }

    // attach 换绑：恢复登录换了 client 后重新 attach 生效（坑7 同类场景）
    @Test
    void reattach_switchesClient() throws Exception {
        adapter.attach(client);
        when(client.getUpdates()).thenReturn(List.of(weixinMsg("u1", "旧客户端")));

        ILinkClient newClient = mock(ILinkClient.class);
        when(newClient.getUpdates()).thenReturn(List.of(weixinMsg("u1", "新客户端")));
        adapter.attach(newClient);

        assertEquals("新客户端", adapter.receive().text());
    }

    // 多媒体识别：图片消息 → mediaType=IMAGE + 下载字节填 mediaBytes
    @Test
    void convert_imageMessage_downloadsBytes() throws Exception {
        WeixinMessage msg = new WeixinMessage();
        msg.setFrom_user_id("u1");
        MessageItem item = new MessageItem();
        ImageItem img = new ImageItem();
        img.setUrl("https://cdn.example.com/a.png");
        item.setImage_item(img);
        msg.setItem_list(List.of(item));

        byte[] downloaded = new byte[]{11, 22, 33};
        when(client.downloadMediaFromMessageItem(item)).thenReturn(downloaded);
        adapter.attach(client);

        AgentMessage am = adapter.convert(msg);

        assertEquals(MediaType.IMAGE, am.mediaType());
        assertArrayEquals(downloaded, am.mediaBytes());
    }

    // 多媒体识别：语音消息 → mediaType=VOICE + 下载字节
    @Test
    void convert_voiceMessage_downloadsBytes() throws Exception {
        WeixinMessage msg = new WeixinMessage();
        msg.setFrom_user_id("u1");
        MessageItem item = new MessageItem();
        item.setVoice_item(new VoiceItem());
        msg.setItem_list(List.of(item));

        byte[] downloaded = new byte[]{1, 2, 3};
        when(client.downloadMediaFromMessageItem(item)).thenReturn(downloaded);
        adapter.attach(client);

        AgentMessage am = adapter.convert(msg);

        assertEquals(MediaType.VOICE, am.mediaType());
        assertArrayEquals(downloaded, am.mediaBytes());
    }

    // 多媒体识别：下载失败 → 不抛异常，mediaType 仍标注、字节为 null（下游降级）
    @Test
    void convert_downloadFailure_degrades() throws Exception {
        WeixinMessage msg = new WeixinMessage();
        msg.setFrom_user_id("u1");
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        msg.setItem_list(List.of(item));

        when(client.downloadMediaFromMessageItem(item)).thenThrow(new java.io.IOException("下载失败"));
        adapter.attach(client);

        AgentMessage am = adapter.convert(msg);

        assertEquals(MediaType.IMAGE, am.mediaType());
        assertNull(am.mediaBytes());
    }

    // 分片模拟流式：超长回复按 200 字逐片发送（收集全部 sendText 拼回原文）+ typing 状态包裹
    @Test
    void send_longText_chunkedWithTyping() throws Exception {
        adapter.attach(client);
        String longText = "字".repeat(500);
        java.util.List<String> sent = new java.util.ArrayList<>();
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(inv.getArgument(1));
            return null;
        }).when(client).sendText(org.mockito.ArgumentMatchers.eq("u1"), captor.capture());

        adapter.send("u1", longText);

        verify(client).startTyping("u1");
        verify(client).stopTyping("u1");
        assertEquals(3, sent.size(), "500 字应切 200+200+100 三片");
        assertTrue(sent.get(0).length() <= ILinkMessageAdapter.CHUNK_SIZE, "每片不超过分片大小");
        assertEquals(longText, String.join("", sent), "分片拼回应与原文一致");
    }

    // 长消息截断保护：超过 MAX_CHUNKS 片 → 截断 + 尾部提示
    @Test
    void send_overLimit_truncates() throws Exception {
        adapter.attach(client);
        String huge = "字".repeat(ILinkMessageAdapter.CHUNK_SIZE * ILinkMessageAdapter.MAX_CHUNKS + 1);

        java.util.List<String> sent = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(inv.getArgument(1));
            return null;
        }).when(client).sendText(org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.anyString());

        adapter.send("u1", huge);

        assertEquals(ILinkMessageAdapter.MAX_CHUNKS, sent.size(), "最多发 MAX_CHUNKS 片");
        assertTrue(sent.get(sent.size() - 1).contains("已截断"), "末片应带截断提示");
    }

    // 分片工具：空串/短文本不分片，长文本按 200 字切
    @Test
    void split_chunksBySize() {
        assertEquals(0, ILinkMessageAdapter.split("").size());
        assertEquals(1, ILinkMessageAdapter.split("短文本").size());
        assertEquals(3, ILinkMessageAdapter.split("字".repeat(450)).size());
        assertEquals(1, ILinkMessageAdapter.split("字".repeat(200)).size(), "正好 200 字一片");
    }
}