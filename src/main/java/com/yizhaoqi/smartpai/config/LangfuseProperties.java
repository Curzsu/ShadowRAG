package com.yizhaoqi.smartpai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Never generate a toString for configuration containing credentials. */
@Getter
@Setter
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {
    private boolean enabled;
    private String baseUrl = "https://jp.cloud.langfuse.com";
    private String publicKey = "";
    private String secretKey = "";
    private String environment = "development";
    // Reserved for explicitly sanitized evaluation. Chat content is never captured in this release.
    private boolean captureContent;
}
