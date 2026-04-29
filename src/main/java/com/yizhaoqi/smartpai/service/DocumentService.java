package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档管理服务类
 * 负责文档的删除等管理操作
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    /**
     * 删除文档及其相关数据
     * 该方法将删除:
     * 1. FileUpload记录
     * 2. DocumentVector记录
     * 3. MinIO中的文件
     * 4. Elasticsearch中的向量数据
     *
     * @param fileMd5 文件MD5
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        logger.info("开始删除文档: {}", fileMd5);
        
        try {
            // 获取文件信息以获取文件名
            FileUpload fileUpload = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));
            
            // 1. 删除Elasticsearch中的数据
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("成功从Elasticsearch删除文档: {}", fileMd5);
            } catch (Exception e) {
                logger.error("从Elasticsearch删除文档时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 2. 删除MinIO中的文件
            try {
                String objectName = "merged/" + fileUpload.getFileName();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build()
                );
                logger.info("成功从MinIO删除文件: {}", objectName);
            } catch (Exception e) {
                logger.error("从MinIO删除文件时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }

            // 2.1 删除MinIO中的解析预览文件
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object("parsed/" + fileMd5 + ".md")
                                .build()
                );
                logger.info("成功从MinIO删除解析预览文件: parsed/{}.md", fileMd5);
            } catch (Exception e) {
                logger.error("从MinIO删除解析预览文件时出错: {}", fileMd5, e);
            }
            
            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("成功删除文档向量记录: {}", fileMd5);
            } catch (Exception e) {
                logger.error("删除文档向量记录时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 4. 删除FileUpload记录
            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("成功删除文件上传记录: {}", fileMd5);
            
            logger.info("文档删除完成: {}", fileMd5);
        } catch (Exception e) {
            logger.error("删除文档过程中发生错误: {}", fileMd5, e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户可访问的所有文件列表
     * 包括用户自己的文件、公开文件和用户所属组织的文件（支持层级权限）
     *
     * @param userId 用户ID
     * @param orgTags 用户所属的组织标签（逗号分隔的字符串，仅供兼容性使用）
     * @return 用户可访问的文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("获取用户可访问文件列表: userId={}", userId);

        try {
            // 解析用户：userId 可能是用户名（如 "Lukesu"）或数字 ID（如 "2"）
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
            }

            // FileUpload.userId 存的是 JWT 中的数字 ID，用 user.getId() 查询
            String numericUserId = user.getId().toString();

            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户有效组织标签: {}", userEffectiveTags);

            // 使用有效标签查询文件
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                // 如果用户没有任何组织标签，只返回自己的文件和公开文件
                files = fileUploadRepository.findByUserIdOrIsPublicTrue(numericUserId);
                logger.debug("用户无组织标签，仅返回个人和公开文件");
            } else {
                // 查询用户可访问的所有文件（考虑层级标签）
                files = fileUploadRepository.findAccessibleFilesWithTags(numericUserId, userEffectiveTags);
                logger.debug("使用有效组织标签查询文件");
            }

            logger.info("成功获取用户可访问文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户上传的所有文件列表
     *
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("获取用户上传的文件列表: userId={}", userId);
        
        try {
            List<FileUpload> files = fileUploadRepository.findByUserId(userId);
            logger.info("成功获取用户上传的文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户上传的文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成文件下载链接
     * 
     * @param fileMd5 文件MD5
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String fileMd5) {
        logger.info("生成文件下载链接: fileMd5={}", fileMd5);
        
        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));
            
            // MinIO中的对象路径格式: merged/文件名
            String objectName = "merged/" + fileUpload.getFileName();
            
            // 生成预签名URL，有效期1小时
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("uploads")
                            .object(objectName)
                            .expiry(3600) // 1小时有效期
                            .build()
            );
            
            logger.info("成功生成文件下载链接: fileMd5={}, fileName={}, objectName={}", 
                    fileMd5, fileUpload.getFileName(), objectName);
            return presignedUrl;
        } catch (Exception e) {
            logger.error("生成文件下载链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }
    
    /**
     * 获取文件预览内容
     *
     * @param fileMd5 文件MD5
     * @param fileName 文件名
     * @return 包含 content 和 contentType 的预览结果
     */
    public java.util.Map<String, String> getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("获取文件预览内容: fileMd5={}, fileName={}", fileMd5, fileName);

        try {
            String fileExtension = getFileExtension(fileName).toLowerCase();

            // 1. 对于二进制文档格式（PDF/DOC/DOCX等），优先读取解析后的内容
            if (isParsedDocument(fileExtension)) {
                String parsedContent = readParsedContent(fileMd5);
                if (parsedContent != null) {
                    logger.info("返回解析后的预览内容: fileMd5={}, contentLength={}", fileMd5, parsedContent.length());
                    return java.util.Map.of("content", parsedContent, "contentType", "markdown");
                }
                // 没有解析内容，根据解析状态返回不同提示
                FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                        .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));
                String fileInfo;
                int parseStatus = fileUpload.getParseStatus() != null ? fileUpload.getParseStatus() : 0;
                if (parseStatus == 1) {
                    // 正在解析中
                    fileInfo = String.format(
                        "文件名: %s\n文件大小: %s\n文件类型: %s\n上传时间: %s\n\n⏳ 文件正在解析中，请稍后再试...",
                        fileName, formatFileSize(fileUpload.getTotalSize()), fileExtension.toUpperCase(), fileUpload.getCreatedAt()
                    );
                } else if (parseStatus == 3) {
                    // 解析失败
                    fileInfo = String.format(
                        "文件名: %s\n文件大小: %s\n文件类型: %s\n上传时间: %s\n\n❌ 文件解析失败，请重新上传或下载后查看。",
                        fileName, formatFileSize(fileUpload.getTotalSize()), fileExtension.toUpperCase(), fileUpload.getCreatedAt()
                    );
                } else {
                    // 待解析（0）或其他
                    fileInfo = String.format(
                        "文件名: %s\n文件大小: %s\n文件类型: %s\n上传时间: %s\n\n⏳ 文件正在排队等待解析，请稍后再试...",
                        fileName, formatFileSize(fileUpload.getTotalSize()), fileExtension.toUpperCase(), fileUpload.getCreatedAt()
                    );
                }
                return java.util.Map.of("content", fileInfo, "contentType", "info");
            }

            // 2. 对于真正的文本文件，读取原始内容
            if (isTextFile(fileExtension)) {
                String objectName = "merged/" + fileName;
                try (InputStream inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build())) {

                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    StringBuilder content = new StringBuilder();
                    String line;
                    int bytesRead = 0;
                    int maxBytes = 10240; // 10KB

                    while ((line = reader.readLine()) != null && bytesRead < maxBytes) {
                        content.append(line).append("\n");
                        bytesRead += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    }

                    String result = content.toString();
                    if (bytesRead >= maxBytes) {
                        result += "\n... (内容已截断，仅显示前10KB)";
                    }

                    // .md 文件用 markdown 渲染，其他纯文本文件用 pre 展示
                    String contentType = "md".equalsIgnoreCase(fileExtension) ? "markdown" : "text";
                    logger.info("成功获取文本文件预览内容: fileMd5={}, contentLength={}", fileMd5, result.length());
                    return java.util.Map.of("content", result, "contentType", contentType);
                }
            }

            // 3. 其他非文本文件
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));
            String fileInfo = String.format(
                "文件名: %s\n文件大小: %s\n文件类型: %s\n上传时间: %s\n\n此文件类型不支持预览，请下载后查看。",
                fileName, formatFileSize(fileUpload.getTotalSize()), fileExtension.toUpperCase(), fileUpload.getCreatedAt()
            );
            return java.util.Map.of("content", fileInfo, "contentType", "info");

        } catch (Exception e) {
            logger.error("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return java.util.Map.of("content", "预览失败: " + e.getMessage(), "contentType", "info");
        }
    }

    /**
     * 从 MinIO 读取解析后的预览内容
     */
    private String readParsedContent(String fileMd5) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("uploads")
                        .object("parsed/" + fileMd5 + ".md")
                        .build())) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        } catch (Exception e) {
            logger.debug("未找到解析预览内容: parsed/{}.md", fileMd5);
            return null;
        }
    }

    /**
     * 判断是否为需要解析预览的二进制文档格式
     */
    private boolean isParsedDocument(String extension) {
        String[] parsedExtensions = {"pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx"};
        return Arrays.stream(parsedExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }
    
    /**
     * 判断是否为文本文件
     */
    private boolean isTextFile(String extension) {
        String[] textExtensions = {
            "txt", "md", "html", "htm", "xml", "json",
            "csv", "log", "java", "js", "ts", "py", "cpp", "c", "h", "css",
            "scss", "less", "sql", "yml", "yaml", "properties", "conf", "config"
        };
        
        return Arrays.stream(textExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(Long size) {
        if (size == null) return "未知";
        
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
} 