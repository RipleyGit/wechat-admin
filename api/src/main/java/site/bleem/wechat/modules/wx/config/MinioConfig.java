package site.bleem.wechat.modules.wx.config;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端。凭证未配置时返回 null bean，由调用方判空降级。
 */
@Configuration
public class MinioConfig {
    private static final Logger logger = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        if (!properties.isConfigured()) {
            logger.warn("MinIO 未配置（缺 endpoint/accessKey/secretKey/bucket），私信媒体转存不可用，图文消息降级为纯文字");
            return null;
        }
        String url = (properties.isSecure() ? "https://" : "http://") + properties.getEndpoint();
        logger.info("MinIO 客户端初始化，endpoint={}, bucket={}", properties.getEndpoint(), properties.getBucket());
        return MinioClient.builder()
                .endpoint(url)
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
