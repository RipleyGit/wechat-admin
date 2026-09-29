package site.bleem.wechat.modules.wx.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.IService;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.modules.wx.entity.NotifyChannel;
import site.bleem.wechat.modules.wx.entity.NotifyLog;

import java.util.Map;

/**
 * 通用消息推送
 */
public interface NotifyService extends IService<NotifyChannel> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * code 只在公众号内唯一，所以必须带上 appid
     */
    NotifyChannel getByCode(String appid, String code);

    /**
     * 按通道推送
     *
     * 同步发。收件人是标签或指定 openid，量很小，同步发能把成功数直接返回给调用方，
     * 配错了立刻就能看出来，异步只会留下一条日志没人看。
     *
     * @param channel 通道配置
     * @param vars    变量，render 模式下填进文案的占位符；passthrough 模式下取其中的 content
     * @param source  webhook / manual
     * @return 推送结果，含 recipientCount / successCount / failCount
     */
    NotifyLog dispatch(NotifyChannel channel, JSONObject vars, String source);

    /**
     * 渲染文案，不发送，给页面预览用
     */
    String render(String template, JSONObject vars);

    PageUtils queryLogPage(Map<String, Object> params);
}
