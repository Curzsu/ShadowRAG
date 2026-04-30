package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.minio.MinioClient;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final FileUploadRepository fileUploadRepository;
    @Autowired
    private KafkaConfig kafkaConfig;


    public FileProcessingConsumer(ParseService parseService, VectorizationService vectorizationService,
                                  FileUploadRepository fileUploadRepository) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.fileUploadRepository = fileUploadRepository;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    @Transactional
    public void processTask(FileProcessingTask task) {
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}",
                task.getUserId(), task.getOrgTag(), task.isPublic());

        // 更新解析状态为：解析中
        updateParseStatus(task.getFileMd5(), 1);

        InputStream fileStream = null;
        try {
            // 下载文件
            fileStream = downloadFileFromStorage(task.getFilePath());
            // 在 downloadFileFromStorage 返回后立即检查流是否可读
            if (fileStream == null) {
                throw new IOException("流为空");
            }

            // 强制转换为可缓存流
            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            // 解析文件：根据文件类型选择解析器
            // 纯文本类文件（txt/md/csv/json等）直接读文本，不走 Tika/MinerU
            // MinerU 只处理二进制文档（pdf/doc/docx/ppt/pptx/xls/xlsx等）
            // Tika 处理 MinerU 不支持的非纯文本格式
            if (parseService.shouldUseMinerU() && isMinerUSupportedFile(task.getFileName())) {
                parseService.parseAndSaveByMinerU(task.getFileMd5(), fileStream,
                        task.getFileName(), task.getUserId(), task.getOrgTag(), task.isPublic());
                log.info("MinerU 文件解析完成，fileMd5: {}", task.getFileMd5());
            } else if (isPlainTextFile(task.getFileName())) {
                parseService.parsePlainText(task.getFileMd5(), fileStream,
                        task.getUserId(), task.getOrgTag(), task.isPublic());
                log.info("纯文本直接解析完成，fileMd5: {}", task.getFileMd5());
            } else {
                parseService.parseAndSave(task.getFileMd5(), fileStream,
                        task.getUserId(), task.getOrgTag(), task.isPublic());
                log.info("Tika 文件解析完成，fileMd5: {}", task.getFileMd5());
            }

            // 向量化处理
            vectorizationService.vectorize(task.getFileMd5(),
                    task.getUserId(), task.getOrgTag(), task.isPublic());
            log.info("向量化完成，fileMd5: {}", task.getFileMd5());

            // 更新解析状态为：解析完成
            updateParseStatus(task.getFileMd5(), 2);
        } catch (Exception e) {
            log.error("Error processing task: {}", task, e);
            // 更新解析状态为：解析失败
            updateParseStatus(task.getFileMd5(), 3);
            // 抛出异常让 Kafka 的 DefaultErrorHandler 捕获并触发重试 / 死信
            throw new RuntimeException("Error processing task", e);
        } finally {
            // 确保关闭输入流
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", e);
                }
            }
        }
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null; // 或者抛出异常
        }
    }

    /**
     * MinerU 支持解析的文件扩展名（二进制文档格式）。
     * 纯文本类文件（txt/md/csv/json/代码文件等）不需要 MinerU，直接读文本即可。
     */
    private static final Set<String> MINERU_SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "png", "jpg", "jpeg", "bmp", "tiff"
    );

    /**
     * 纯文本文件扩展名，可以直接读取内容，不需要 Tika/MinerU 解析。
     */
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json", "xml", "html", "htm", "log",
            "java", "js", "ts", "py", "cpp", "c", "h", "css", "scss", "less",
            "sql", "yml", "yaml", "properties", "conf", "config", "sh", "bat"
    );

    /**
     * 判断文件是否应使用 MinerU 解析。
     */
    private boolean isMinerUSupportedFile(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return false;
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return MINERU_SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 判断文件是否为纯文本文件，可以直接读取内容。
     */
    private boolean isPlainTextFile(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return true; // 无扩展名视为纯文本
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return PLAIN_TEXT_EXTENSIONS.contains(ext);
    }

    /**
     * 更新文件解析状态
     * 使用 @Modifying @Query 只更新 parse_status 列，
     * 避免 load→modify→save 全字段写入覆盖其他并发修改（如 status 字段）
     *
     * @param fileMd5 文件MD5
     * @param status 0=待解析, 1=解析中, 2=解析完成, 3=解析失败
     */
    private void updateParseStatus(String fileMd5, int status) {
        try {
            fileUploadRepository.updateParseStatusByFileMd5(fileMd5, status);
            log.info("更新文件解析状态: fileMd5={}, parseStatus={}", fileMd5, status);
        } catch (Exception e) {
            log.warn("更新解析状态失败: fileMd5={}, status={}", fileMd5, status, e);
        }
    }
}