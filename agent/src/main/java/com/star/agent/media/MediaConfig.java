package com.star.agent.media;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 多媒体预处理装配：AudioFormatDetector/AudioTranscoder 是纯类（手动链路也用），
// Spring 链路在这里装配，VoiceTranscriber 自动注入 DashScopeAsrService（真实 ASR）
@Configuration
public class MediaConfig {

    @Bean
    public AudioFormatDetector audioFormatDetector() {
        return new AudioFormatDetector();
    }

    @Bean
    public AudioTranscoder audioTranscoder() {
        return new AudioTranscoder("ffmpeg");
    }

    @Bean
    public MediaPreprocessor mediaPreprocessor(AudioFormatDetector detector,
                                               AudioTranscoder transcoder,
                                               VoiceTranscriber transcriber) {
        return new MediaPreprocessor(detector, transcoder, transcriber);
    }
}