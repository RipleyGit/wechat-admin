package site.bleem.wechat.modules.wx.controller;

import com.alibaba.fastjson.JSONObject;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.config.DeployWebhookProperties;
import site.bleem.wechat.modules.wx.entity.NotifyChannel;
import site.bleem.wechat.modules.wx.entity.NotifyLog;
import site.bleem.wechat.modules.wx.form.DeployWebhookForm;
import site.bleem.wechat.modules.wx.service.NotifyService;

/**
 * 接收 GitHub Actions 部署结果回调
 *
 * 文案和收件人已经搬到 wx_notify_channel（deploy-success / deploy-failure），
 * 这里只剩鉴权和字段转换。路径和鉴权方式都不能动：
 * deploy.yml 拿这个地址当服务启动探针，用一个故意写错的 secret 打过来，
 * 只要能拿到 HTTP 状态码就算服务起来了。
 */
@RestController
@RequestMapping("/deploy")
@RequiredArgsConstructor
@Slf4j
@Api(tags = {"部署通知"})
public class DeployWebhookController {

    private final DeployWebhookProperties deployWebhookProperties;
    private final NotifyService notifyService;

    @PostMapping("/webhook")
    @ApiOperation(value = "接收部署结果并推送微信消息")
    public R webhook(@RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
                      @RequestBody DeployWebhookForm form) {
        if (!StringUtils.hasText(secret) || !secret.equals(deployWebhookProperties.getSecret())) {
            log.warn("部署通知鉴权失败，actor={}", form.getActor());
            return R.error(401, "invalid webhook secret");
        }

        log.info("收到部署通知：actor={}, status={}, branch={}", form.getActor(), form.getStatus(), form.getBranch());

        // 成功和失败是两个通道，文案各自独立，不在这里做值映射
        String code = "success".equals(form.getStatus()) ? "deploy-success" : "deploy-failure";
        NotifyChannel channel = notifyService.getByCode(code);
        if (channel == null) {
            // 迁移脚本漏了 seed。返回 200 是因为 deploy.yml 的通知步骤是 continue-on-error，
            // 报错也不会让部署失败，只会把真正的原因埋在 Actions 日志里
            log.error("部署通知通道[{}]不存在，请检查 wx_notify_channel 是否已初始化", code);
            return R.ok().put("notifySent", 0).put("errorMsg", "channel not found: " + code);
        }
        if (!Boolean.TRUE.equals(channel.getEnabled())) {
            log.info("部署通知通道[{}]已停用，跳过", code);
            return R.ok().put("notifySent", 0);
        }

        NotifyLog result = notifyService.dispatch(channel, buildVars(form), NotifyLog.SOURCE_WEBHOOK);
        return R.ok().put("notifySent", result.getSuccessCount());
    }

    /**
     * 把回调字段摊平成文案变量
     *
     * commit_short 是原来那段 20 字截断，模板里只会做占位替换，做不了截断，所以在这儿算
     */
    private JSONObject buildVars(DeployWebhookForm form) {
        String commitMsg = form.getCommitMsg() == null ? "" : form.getCommitMsg();
        String firstLine = commitMsg.split("\\r?\\n", 2)[0];

        JSONObject vars = new JSONObject();
        vars.put("branch", form.getBranch());
        vars.put("commit_msg", firstLine);
        vars.put("commit_short", firstLine.length() > 20 ? firstLine.substring(0, 20) + "…" : firstLine);
        vars.put("actor", form.getActor());
        vars.put("status", form.getStatus());
        vars.put("deploy_time", form.getDeployTime());
        vars.put("failed_step", form.getFailedStep());
        vars.put("run_url", form.getRunUrl());
        return vars;
    }
}
