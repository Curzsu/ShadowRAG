package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Generation generation = new Generation();
    private Context context = new Context();

    @Data
    public static class Prompt {
        /** 规则文案 */
        private String rules;
        /** 引用开始分隔符 */
        private String refStart;
        /** 引用结束分隔符 */
        private String refEnd;
        /** 无检索结果时的占位文案 */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 最大输出 tokens */
        private Integer maxTokens = 2000;
        /** nucleus top-p */
        private Double topP = 0.9;
    }

    @Data
    public static class Context {
        /** 模型上下文窗口；应按实际部署模型配置 */
        private int windowTokens = 65536;
        /** 为 tokenizer 误差、消息封装和供应商差异预留的安全空间 */
        private int safetyMarginTokens = 2048;
        /** 拼入提示词的最近摘要段数量上限，避免摘要本身无限增长 */
        private int maxSummarySegments = 3;
    }
}
