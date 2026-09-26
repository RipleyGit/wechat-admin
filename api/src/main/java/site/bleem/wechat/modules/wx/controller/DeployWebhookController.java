package site.bleem.wechat.modules.wx.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import me.chanjar.weixin.mp.bean.tag.WxUserTag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.config.DeployWebhookProperties;
import site.bleem.wechat.modules.wx.entity.WxUser;
import site.bleem.wechat.modules.wx.form.DeployWebhookForm;
import site.bleem.wechat.modules.wx.service.WxUserService;
import site.bleem.wechat.modules.wx.service.WxUserTagsService;

import java.util.List;

/**
 * 接收 GitHub Actions 部署结果回调，推送给标记了指定标签的粉丝
 */
@RestController
@RequestMapping("/deploy")
@RequiredArgsConstructor
@Slf4j
@Api(tags = {"部署通知"})
public class DeployWebhookController {

    private final WxMpService wxMpService;
    private final WxUserTagsService wxUserTagsService;
    private final WxUserService wxUserService;
    private final DeployWebhookProperties deployWebhookProperties;

    @PostMapping("/webhook")
    @ApiOperation(value = "接收部署结果并推送微信客服消息")
    public R webhook(@RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
                      @RequestBody DeployWebhookForm form) {
        if (!StringUtils.hasText(secret) || !secret.equals(deployWebhookProperties.getSecret())) {
            log.warn("部署通知鉴权失败，actor={}", form.getActor());
            return R.error(401, "invalid webhook secret");
        }

        log.info("收到部署通知：actor={}, status={}, branch={}", form.getActor(), form.getStatus(), form.getBranch());

        String appid = deployWebhookProperties.getAppid();
        String tagName = deployWebhookProperties.getTagName();
        try {
            wxMpService.switchoverTo(appid);
            Long tagId = findTagIdByName(appid, tagName);
            if (tagId == null) {
                log.warn("未找到标签[{}]，appid={}，跳过推送", tagName, appid);
                return R.ok().put("notifySent", 0);
            }

            List<WxUser> fans = wxUserService.list(new QueryWrapper<WxUser>()
                    .eq("appid", appid)
                    .eq("subscribe", true)
                    .apply("JSON_CONTAINS(tagid_list,{0})", String.valueOf(tagId)));

            String text = buildNotifyText(form);
            int sent = 0;
            for (WxUser fan : fans) {
                try {
                    wxMpService.getKefuService().sendKefuMessage(
                            WxMpKefuMessage.TEXT().toUser(fan.getOpenid()).content(text).build());
                    sent++;
                } catch (Exception e) {
                    log.error("推送部署通知失败，openid={}", fan.getOpenid(), e);
                }
            }
            log.info("部署通知推送完成，标签[{}]粉丝数={}，成功={}", tagName, fans.size(), sent);
            return R.ok().put("notifySent", sent);
        } catch (Exception e) {
            log.error("处理部署通知出错", e);
            return R.error("push deploy notification failed: " + e.getMessage());
        }
    }

    private Long findTagIdByName(String appid, String tagName) throws me.chanjar.weixin.common.error.WxErrorException {
        List<WxUserTag> tags = wxUserTagsService.getWxTags(appid);
        for (WxUserTag tag : tags) {
            if (tagName.equals(tag.getName())) {
                return tag.getId();
            }
        }
        return null;
    }

    private String buildNotifyText(DeployWebhookForm form) {
        boolean success = "success".equals(form.getStatus());
        String icon = success ? "✅" : "❌";
        String result = success ? "部署成功" : "部署失败";

        String commitMsg = form.getCommitMsg() == null ? "" : form.getCommitMsg();
        String firstLine = commitMsg.split("\\r?\\n", 2)[0];
        String shortMsg = firstLine.length() > 20 ? firstLine.substring(0, 20) + "…" : firstLine;

        StringBuilder sb = new StringBuilder();
        sb.append(icon).append(' ').append(shortMsg).append(' ').append(result).append('\n');
        sb.append("分支：").append(form.getBranch()).append('\n');
        sb.append("提交：").append(firstLine).append('\n');
        sb.append("作者：").append(form.getActor()).append('\n');
        sb.append("时间：").append(form.getDeployTime());

        if (!success) {
            if (StringUtils.hasText(form.getFailedStep())) {
                sb.append('\n').append("失败步骤：").append(form.getFailedStep());
            }
            if (StringUtils.hasText(form.getRunUrl())) {
                sb.append('\n').append("日志：").append(form.getRunUrl());
            }
        }
        return sb.toString();
    }
}
