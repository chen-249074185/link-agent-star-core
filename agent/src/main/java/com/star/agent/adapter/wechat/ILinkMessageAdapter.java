package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import com.star.agent.message.MessageSource;

import java.time.Instant;

//
public class ILinkMessageAdapter {

    public AgentMessage convert(WeixinMessage msg){
        String userId = msg.getFrom_user_id();

        StringBuilder text = new StringBuilder();
        if(msg.getItem_list() != null){
            for(var item : msg.getItem_list()){
                if(item.getText_item() != null){
                    text.append(item.getText_item().getText());
                }
            }
        }
        return  new AgentMessage(
                MessageSource.WECHAT,   // source：固定微信
                userId,                 // userId：第一步拿到的
                text.toString(),        // text：第二步拼好的文本
                MediaType.TEXT,         // mediaType：今天只做文本，固定 TEXT
                null,                   // mediaBytes：没有媒体，先 null
                Instant.now()           // timestamp：当前时间
        );
    }
}
