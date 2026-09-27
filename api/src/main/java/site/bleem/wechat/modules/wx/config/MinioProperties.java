package site.bleem.wechat.modules.wx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 私信媒体转存用的对象存储配置
 *
 * 注意：不给 accessKey/secretKey 任何默认值。没配置时 {@link #isConfigured()} 返回 false，
 * MinioConfig 跳过客户端初始化，私信降级为纯文字可用。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {
    /**
     * host:port，不带协议
     */
    private String endpoint;
    private String accessKey;
    private String secretKey;
    /**
     * 桶名，需提前建好并开放匿名读
     */
    private String bucket;
    /**
     * 是否用 https 连接 MinIO
     */
    private boolean secure;
    /**
     * 对外访问前缀（如 https://example.com/minio），供浏览器直接取对象。
     * 留空时回退到 endpoint 直连。
     */
    private String publicBaseUrl;

    public boolean isConfigured() {
        return StringUtils.hasText(endpoint)
                && StringUtils.hasText(accessKey)
                && StringUtils.hasText(secretKey)
                && StringUtils.hasText(bucket);
    }

    /**
     * 拼对象的公开访问地址
     */
    public String publicUrl(String objectName) {
        String base = StringUtils.hasText(publicBaseUrl)
                ? publicBaseUrl
                : (secure ? "https://" : "http://") + endpoint + "/" + bucket;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + objectName;
    }
}
