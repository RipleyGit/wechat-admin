package site.bleem.wechat.modules.wx.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.MsgTemplate;
import site.bleem.wechat.modules.wx.entity.NotifyChannel;
import site.bleem.wechat.modules.wx.service.MsgTemplateService;
import site.bleem.wechat.modules.wx.service.NotifyService;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扫码查看通道的模板配置。
 *
 * 运营台在通道列表点「二维码」生成 token，二维码内容是本接口地址加 token。
 * 手机扫码后直接看到模板 ID 和字段映射（templateData），方便对接方照着拼请求。
 * 接口匿名，数据本身不敏感，靠 10 分钟的随机 token 限制暴露面。
 */
@RestController
@RequestMapping("/channel-info")
@RequiredArgsConstructor
public class ChannelInfoController {

    /** token → 通道 id，由 NotifyChannelManageController 写入 */
    public static final String TOKEN_KEY = "wx:channel:info:";
    public static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;
    private final NotifyService notifyService;
    private final MsgTemplateService msgTemplateService;

    @GetMapping("/{token}")
    public R info(@PathVariable String token) {
        Object id = redisTemplate.opsForValue().get(TOKEN_KEY + token);
        if (id == null) {
            throw new RRException("二维码已失效，请在运营台刷新后重新扫码", 400);
        }
        NotifyChannel channel = notifyService.getById(Long.valueOf(String.valueOf(id)));
        if (channel == null) {
            throw new RRException("通道不存在", 404);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", channel.getName());
        data.put("code", channel.getCode());
        data.put("sendType", channel.getSendType());
        data.put("templateId", channel.getTemplateId());
        data.put("templateUrl", channel.getTemplateUrl());
        data.put("templateData", channel.getTemplateData());
        data.put("contentTemplate", channel.getContentTemplate());

        // 模板正文一起带出来，扫码的人能直接看到 {{keyword1.DATA}} 和映射的对应关系
        if (StringUtils.hasText(channel.getTemplateId())) {
            MsgTemplate template = msgTemplateService.getOne(new QueryWrapper<MsgTemplate>()
                .eq("appid", channel.getAppid())
                .eq("template_id", channel.getTemplateId())
                .last("LIMIT 1"), false);
            if (template != null) {
                data.put("templateTitle", template.getTitle());
                data.put("templateContent", template.getContent());
            }
        }
        return R.ok().put(data);
    }
}
