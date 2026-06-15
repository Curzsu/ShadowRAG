package com.yizhaoqi.smartpai.config;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private static final Logger logger = LoggerFactory.getLogger(MinioConfig.class);

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Value("${minio.publicUrl}")
    private String publicUrl;

    @Value("${minio.bucketName}")
    private String bucketName;


    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        ensureBucketExists(client);
        return client;
    }

    @Bean
    public String minioPublicUrl() {
        return publicUrl;
    }

    /**
     * 启动时自动检查并创建 MinIO bucket，避免因 bucket 缺失导致上传失败。
     * 原先依赖手动 mc mb 创建，换环境或重建 MinIO 后会反复踩 NoSuchBucket。
     */
    private void ensureBucketExists(MinioClient client) {
        try {
            boolean exists = client.bucketExists(
                    io.minio.BucketExistsArgs.builder().bucket(bucketName).build());
            if (exists) {
                logger.info("MinIO bucket 已存在 => bucket: {}", bucketName);
            } else {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                logger.info("MinIO bucket 自动创建成功 => bucket: {}", bucketName);
            }
        } catch (Exception e) {
            // bucket 初始化失败不阻断启动，避免 MinIO 短暂不可用时整个服务起不来
            logger.error("MinIO bucket 初始化失败 => bucket: {}, 错误: {}", bucketName, e.getMessage(), e);
        }
    }
}
