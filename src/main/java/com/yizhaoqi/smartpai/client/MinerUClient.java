package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class MinerUClient {

    private static final Logger logger = LoggerFactory.getLogger(MinerUClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${mineru.api.timeout:300}")
    private int timeoutSeconds;

    public MinerUClient(@Qualifier("mineruWebClient") WebClient mineruWebClient,
                        ObjectMapper objectMapper) {
        this.webClient = mineruWebClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 MinerU /file_parse 同步接口，将文件解析为 Markdown 文本。
     *
     * @param fileStream 文件输入流
     * @param fileName   文件名（用于判断格式和作为上传字段名）
     * @return 解析后的 Markdown 纯文本
     */
    public String parseToMarkdown(InputStream fileStream, String fileName) {
        try {
            logger.info("开始调用 MinerU 解析文件: {}", fileName);

            // 将 InputStream 读入 byte[]，因为 multipart 上传需要知道长度
            byte[] fileBytes = readAllBytes(fileStream);
            logger.debug("文件大小: {} bytes", fileBytes.length);

            String response = webClient.post()
                    .uri("/file_parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromMultipartData(
                            "files", new ByteArrayResource(fileBytes) {
                                @Override
                                public String getFilename() {
                                    return fileName;
                                }
                            }
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(5))
                            .filter(e -> e instanceof WebClientResponseException))
                    .block(Duration.ofSeconds(timeoutSeconds));

            if (response == null) {
                throw new RuntimeException("MinerU 返回为空，可能超时");
            }

            String markdown = extractMarkdown(response);
            logger.info("MinerU 解析完成，Markdown 长度: {} 字符", markdown.length());
            return markdown;

        } catch (Exception e) {
            logger.error("MinerU 解析失败: {}", e.getMessage(), e);
            throw new RuntimeException("MinerU 文件解析失败", e);
        }
    }

    /**
     * 从 MinerU /file_parse 的 JSON 响应中提取 Markdown 文本。
     * MinerU 的响应格式为：
     * {
     *   "status": "success",
     *   "data": {
     *     "文件名": {
     *       "markdown": "...",
     *       "content_list": [...],
     *       ...
     *     }
     *   }
     * }
     */
    private String extractMarkdown(String response) throws IOException {
        JsonNode root = objectMapper.readTree(response);

        // MinerU 3.0 格式: { "results": { "文件名": { "md_content": "..." } } }
        JsonNode resultsNode = root.get("results");
        if (resultsNode != null && !resultsNode.isEmpty()) {
            String firstKey = resultsNode.fieldNames().next();
            JsonNode fileNode = resultsNode.get(firstKey);

            // 优先取 md_content（MinerU 3.0+）
            JsonNode mdNode = fileNode.get("md_content");
            if (mdNode != null && !mdNode.isMissingNode() && !mdNode.isNull()) {
                return mdNode.asText();
            }
            // 兼容旧字段名 markdown
            mdNode = fileNode.get("markdown");
            if (mdNode != null && !mdNode.isMissingNode() && !mdNode.isNull()) {
                return mdNode.asText();
            }
        }

        // 旧版格式: { "data": { "文件名": { "markdown": "..." } } }
        JsonNode dataNode = root.get("data");
        if (dataNode != null && !dataNode.isEmpty()) {
            String firstKey = dataNode.fieldNames().next();
            JsonNode fileNode = dataNode.get(firstKey);

            JsonNode mdNode = fileNode.get("markdown");
            if (mdNode != null && !mdNode.isMissingNode() && !mdNode.isNull()) {
                return mdNode.asText();
            }
        }

        // 兜底：直接尝试取顶层 markdown 字段
        JsonNode markdownNode = root.get("markdown");
        if (markdownNode != null && !markdownNode.isMissingNode()) {
            return markdownNode.asText();
        }

        logger.warn("无法从 MinerU 响应中提取 markdown，原始响应前 500 字符: {}",
                response.length() > 500 ? response.substring(0, 500) : response);
        throw new RuntimeException("MinerU 响应格式异常，无法提取 Markdown");
    }

    /**
     * 检查 MinerU 服务是否可用
     */
    public boolean isAvailable() {
        try {
            String result = webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
            return result != null;
        } catch (Exception e) {
            logger.warn("MinerU 服务不可用: {}", e.getMessage());
            return false;
        }
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }
}
