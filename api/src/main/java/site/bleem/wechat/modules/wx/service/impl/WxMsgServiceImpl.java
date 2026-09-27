package site.bleem.wechat.modules.wx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.common.utils.Query;
import lombok.RequiredArgsConstructor;
import me.chanjar.weixin.common.api.WxConsts;
import site.bleem.wechat.modules.wx.dao.WxMsgMapper;
import site.bleem.wechat.modules.wx.entity.WxMsg;
import site.bleem.wechat.modules.wx.service.MediaStoreService;
import site.bleem.wechat.modules.wx.service.WxMsgService;
import site.bleem.wechat.modules.wx.service.WxMsgSessionService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;


@Service("wxMsgService")
@RequiredArgsConstructor
public class WxMsgServiceImpl extends ServiceImpl<WxMsgMapper, WxMsg> implements WxMsgService {

    private final WxMsgSessionService wxMsgSessionService;
    private final MediaStoreService mediaStoreService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        String msgTypes = (String)params.get("msgTypes");
        String startTime = (String)params.get("startTime");
        String openid = (String)params.get("openid");
        String appid = (String) params.get("appid");
        IPage<WxMsg> page = this.page(
                new Query<WxMsg>().getPage(params),
                new QueryWrapper<WxMsg>()
                        .eq(StringUtils.hasText(appid), "appid", appid)
                        .in(StringUtils.hasText(msgTypes),"msg_type", Arrays.asList(msgTypes.split(",")))
                        .eq(StringUtils.hasText(openid),"openid",openid)
                        .ge(StringUtils.hasText(startTime),"create_time",startTime)
        );

        return new PageUtils(page);
    }

    /**
     * 记录msg，异步入库
     * @param msg
     */
    @Override
    @Async
    public void addWxMsg(WxMsg msg) {
        this.addWxMsgSync(msg);
    }

    @Override
    public void addWxMsgSync(WxMsg msg) {
        this.baseMapper.insert(msg);
        // 派生会话状态，异常已在内部吞掉
        wxMsgSessionService.onMsgSaved(msg);
        tryTransferMedia(msg);
    }

    @Override
    public List<WxMsg> listTimeline(String appid, String openid, Long beforeId, int limit) {
        return this.list(new QueryWrapper<WxMsg>()
                .eq("appid", appid)
                .eq("openid", openid)
                .notIn("msg_type", WxMsgSessionService.HIDDEN_MSG_TYPES)
                .lt(beforeId != null, "id", beforeId)
                .orderByDesc("id")
                .last("LIMIT " + limit));
    }

    /**
     * 入站图片和语音转存到对象存储
     *
     * mediaId 三天过期。图片还有 picUrl 兜底，语音在微信侧没有任何长期地址，
     * 不转存的话过期后原始音频就永久没了，而识别文字又是空的（见 docs/fan-messaging.md），
     * 那条消息就彻底不可恢复。所以语音必须存。
     */
    private void tryTransferMedia(WxMsg msg) {
        if (msg.getInOut() != WxMsg.WxMsgInOut.IN || msg.getDetail() == null) {
            return;
        }
        String msgType = msg.getMsgType();
        boolean isImage = WxConsts.XmlMsgType.IMAGE.equals(msgType);
        boolean isVoice = WxConsts.XmlMsgType.VOICE.equals(msgType);
        if (!isImage && !isVoice) {
            return;
        }
        String mediaId = msg.getDetail().getString("mediaId");
        if (!StringUtils.hasText(mediaId)) {
            return;
        }
        // 语音用微信给的 format（amr），比下载响应头推断可靠
        String preferExt = isVoice ? msg.getDetail().getString("format") : null;
        mediaStoreService.transferInboundMediaAsync(msg.getId(), msg.getAppid(), mediaId, preferExt);
    }
}