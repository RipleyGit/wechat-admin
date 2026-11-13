package site.bleem.wechat.modules.wx.form;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用模板消息推送结果。字段语义见 docs/模板消息推送设计方案.md §2.3
 */
@Data
public class TemplatePushResult {

    private String batchId;
    private int requested;
    private int resolved;
    private int duplicate;
    private int successCount;
    private int failCount;
    /** 发送阶段单条失败，最多 50 条；完整记录落库 */
    private List<Failure> failures = new ArrayList<>();

    @Data
    public static class Failure {
        private String openid;
        private String reason;

        public Failure() {
        }

        public Failure(String openid, String reason) {
            this.openid = openid;
            this.reason = reason;
        }
    }
}
