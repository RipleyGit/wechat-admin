package site.bleem.wechat.modules.wx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "deploy.webhook")
public class DeployWebhookProperties {
    /**
     * 接收部署通知推送的公众号 appid
     */
    private String appid;
    /**
     * GitHub Actions 请求鉴权密钥，对应请求头 X-Webhook-Secret
     */
    private String secret;
    /**
     * 接收推送的粉丝标签名称
     */
    private String tagName;
}
