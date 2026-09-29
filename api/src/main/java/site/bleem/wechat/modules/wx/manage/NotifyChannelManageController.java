package site.bleem.wechat.modules.wx.manage;

import java.util.Arrays;
import java.util.Map;

import com.alibaba.fastjson.JSONObject;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.NotifyChannel;
import site.bleem.wechat.modules.wx.entity.NotifyLog;
import site.bleem.wechat.modules.wx.service.NotifyService;

/**
 * 消息推送通道-管理后台
 *
 * 网关入口在 NotifyGatewayController（/notify/**，不走登录拦截）。
 * 这里是配置和手动发，都在 /manage/** 下面，走 Shiro。
 * 推送密钥是公众号级的，在公众号配置里生成，见 WxAccountConfigController。
 */
@RestController
@RequestMapping("/manage/notifyChannel")
@RequiredArgsConstructor
@Api(tags = {"消息推送通道-管理后台"})
public class NotifyChannelManageController {
    private final NotifyService notifyService;

    /**
     * 列表
     */
    @GetMapping("/list")
    @RequiresPermissions("wx:notifychannel:list")
    @ApiOperation(value = "列表")
    public R list(@CookieValue String appid, @RequestParam Map<String, Object> params) {
        params.put("appid", appid);
        PageUtils page = notifyService.queryPage(params);

        return R.ok().put("page", page);
    }

    /**
     * 信息
     */
    @GetMapping("/info/{id}")
    @RequiresPermissions("wx:notifychannel:info")
    @ApiOperation(value = "详情")
    public R info(@CookieValue String appid, @PathVariable("id") Long id) {
        NotifyChannel channel = notifyService.getById(id);

        return R.ok().put("notifyChannel", belongsTo(channel, appid) ? channel : null);
    }

    /**
     * 保存
     */
    @PostMapping("/save")
    @RequiresPermissions("wx:notifychannel:save")
    @ApiOperation(value = "保存")
    public R save(@CookieValue String appid, @RequestBody NotifyChannel channel) {
        // appid 一律用 Cookie 里的，不信请求体，避免往别的公众号名下塞通道
        channel.setAppid(appid);
        channel.setId(null);
        R invalid = validate(channel);
        if (invalid != null) {
            return invalid;
        }
        if (notifyService.getByCode(appid, channel.getCode()) != null) {
            return R.error("通道标识[" + channel.getCode() + "]已存在");
        }
        normalize(channel);
        notifyService.save(channel);

        return R.ok();
    }

    /**
     * 修改
     */
    @PostMapping("/update")
    @RequiresPermissions("wx:notifychannel:update")
    @ApiOperation(value = "修改")
    public R update(@CookieValue String appid, @RequestBody NotifyChannel channel) {
        NotifyChannel exists = notifyService.getById(channel.getId());
        if (!belongsTo(exists, appid)) {
            return R.error("通道不存在");
        }
        channel.setAppid(appid);
        // code 是网关地址的一部分，改了等于换地址，调用方那边会静默失败，不给改
        channel.setCode(exists.getCode());
        // 没传的字段沿用库里的值，不能拿新建时的默认值去填：
        // 否则一个不带 enabled 的请求会把停用的通道重新启用，不带 gatewayEnabled 会把网关关掉
        keepUnset(channel, exists);
        R invalid = validate(channel);
        if (invalid != null) {
            return invalid;
        }
        notifyService.updateById(channel);

        return R.ok();
    }

    /**
     * 删除
     */
    @PostMapping("/delete")
    @RequiresPermissions("wx:notifychannel:delete")
    @ApiOperation(value = "删除")
    public R delete(@CookieValue String appid, @RequestBody Long[] ids) {
        for (Long id : ids) {
            if (!belongsTo(notifyService.getById(id), appid)) {
                return R.error("通道不存在");
            }
        }
        notifyService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

    /**
     * 预览渲染结果
     *
     * 不落日志、不发消息。变量给空就用通道最近一次收到的 payload，
     * 省得运营为了看一眼文案自己手敲一遍变量。
     */
    @PostMapping("/preview")
    @RequiresPermissions("wx:notifychannel:info")
    @ApiOperation(value = "预览渲染结果")
    public R preview(@CookieValue String appid, @RequestBody JSONObject body) {
        Long id = body.getLong("id");
        NotifyChannel channel = id == null ? null : notifyService.getById(id);
        if (id != null && !belongsTo(channel, appid)) {
            return R.error("通道不存在");
        }
        String template = body.getString("contentTemplate");
        JSONObject vars = body.getJSONObject("vars");
        if (vars == null || vars.isEmpty()) {
            vars = channel == null || channel.getLastPayload() == null ? new JSONObject() : channel.getLastPayload();
        }

        return R.ok().put("content", notifyService.render(template, vars)).put("vars", vars);
    }

    /**
     * 手动发送
     */
    @PostMapping("/send")
    @RequiresPermissions("wx:notifychannel:send")
    @ApiOperation(value = "手动发送")
    public R send(@CookieValue String appid, @RequestBody JSONObject body) {
        NotifyChannel channel = notifyService.getById(body.getLong("id"));
        if (!belongsTo(channel, appid)) {
            return R.error("通道不存在");
        }
        // 停用的通道手动也不让发：停用就是「先别发了」，页面上绕过去就没意义了
        if (!Boolean.TRUE.equals(channel.getEnabled())) {
            return R.error("通道已停用，请先启用");
        }
        JSONObject vars = body.getJSONObject("vars");
        NotifyLog result = notifyService.dispatch(channel,
                vars == null ? new JSONObject() : vars, NotifyLog.SOURCE_MANUAL);

        return R.ok()
                .put("recipientCount", result.getRecipientCount())
                .put("successCount", result.getSuccessCount())
                .put("failCount", result.getFailCount())
                .put("content", result.getContent())
                .put("errorMsg", result.getErrorMsg());
    }

    /**
     * 推送记录
     */
    @GetMapping("/logs")
    @RequiresPermissions("wx:notifychannel:list")
    @ApiOperation(value = "推送记录")
    public R logs(@CookieValue String appid, @RequestParam Map<String, Object> params) {
        params.put("appid", appid);
        PageUtils page = notifyService.queryLogPage(params);

        return R.ok().put("page", page);
    }

    /**
     * 通道归属校验
     *
     * getById 是全表查，不带 appid 条件，多公众号下不校验就能改到别人的通道。
     */
    private boolean belongsTo(NotifyChannel channel, String appid) {
        return channel != null && channel.getAppid() != null && channel.getAppid().equals(appid);
    }

    /**
     * 存之前把不合法的组合挡掉
     *
     * 这些组合存下去不会报错，但会在真正推送的时候才炸，那时候是调用方看到失败，排查得绕一圈。
     */
    private R validate(NotifyChannel channel) {
        if (!StringUtils.hasText(channel.getCode())) {
            return R.error("通道标识不能为空");
        }
        if (!channel.getCode().matches("[a-zA-Z0-9_-]{1,64}")) {
            // code 直接拼进 URL，限死字符集，免得出现要转义的地址
            return R.error("通道标识只能用字母、数字、下划线和短横线");
        }
        if (!StringUtils.hasText(channel.getName())) {
            return R.error("通道名称不能为空");
        }
        if (!StringUtils.hasText(channel.getRecipientValue())) {
            return R.error("收件人不能为空");
        }
        boolean passthrough = NotifyChannel.CONTENT_MODE_PASSTHROUGH.equals(channel.getContentMode());
        boolean template = NotifyChannel.SEND_TYPE_TEMPLATE.equals(channel.getSendType());
        if (passthrough && template) {
            // 模板消息是按字段填值的，没有「整段文案」这个位置可以透传
            return R.error("模板消息不支持透传模式，请改用渲染模式");
        }
        if (!passthrough && !StringUtils.hasText(channel.getContentTemplate()) && !template) {
            return R.error("渲染模式下文案不能为空");
        }
        if (template && !StringUtils.hasText(channel.getTemplateId())) {
            return R.error("模板消息需要填写模板 ID");
        }

        return null;
    }

    private void keepUnset(NotifyChannel channel, NotifyChannel exists) {
        if (channel.getEnabled() == null) {
            channel.setEnabled(exists.getEnabled());
        }
        if (channel.getGatewayEnabled() == null) {
            channel.setGatewayEnabled(exists.getGatewayEnabled());
        }
        if (!StringUtils.hasText(channel.getSendType())) {
            channel.setSendType(exists.getSendType());
        }
        if (!StringUtils.hasText(channel.getRecipientType())) {
            channel.setRecipientType(exists.getRecipientType());
        }
        if (!StringUtils.hasText(channel.getContentMode())) {
            channel.setContentMode(exists.getContentMode());
        }
    }

    /**
     * 新建时补默认值
     *
     * 页面上没填的字段不能留 NULL：这几列在库里是 NOT NULL，
     * 而且严格模式下 INSERT 会直接失败。
     */
    private void normalize(NotifyChannel channel) {
        // 默认不开放网关：同一个公众号的通道共用推送密钥，新通道要显式打开才能被外部触发
        if (channel.getGatewayEnabled() == null) {
            channel.setGatewayEnabled(false);
        }
        if (channel.getEnabled() == null) {
            channel.setEnabled(true);
        }
        if (!StringUtils.hasText(channel.getSendType())) {
            channel.setSendType(NotifyChannel.SEND_TYPE_KEFU);
        }
        if (!StringUtils.hasText(channel.getRecipientType())) {
            channel.setRecipientType(NotifyChannel.RECIPIENT_TYPE_TAG);
        }
        if (!StringUtils.hasText(channel.getContentMode())) {
            channel.setContentMode(NotifyChannel.CONTENT_MODE_RENDER);
        }
    }
}
