package site.bleem.wechat.modules.wx.form;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * GitHub Actions 部署回调请求体
 */
@Data
public class DeployWebhookForm {
    private String branch;
    @JsonProperty("commit_msg")
    private String commitMsg;
    private String actor;
    /**
     * success 或 failure
     */
    private String status;
    @JsonProperty("run_url")
    private String runUrl;
    @JsonProperty("deploy_time")
    private String deployTime;
    @JsonProperty("failed_step")
    private String failedStep;
}
