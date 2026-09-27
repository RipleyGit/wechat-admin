package site.bleem.wechat.modules.wx.service;

import com.baomidou.mybatisplus.extension.service.IService;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.modules.wx.entity.WxMsg;

import java.util.List;
import java.util.Map;

/**
 * 微信消息
 *
 * @author niefy
 * @date 2020-05-14 17:28:34
 */
public interface WxMsgService extends IService<WxMsg> {
    /**
     * 分页查询用户数据
     * @param params 查询参数
     * @return PageUtils 分页结果
     */
    PageUtils queryPage(Map<String, Object> params);

    /**
     * 记录msg，异步入库
     *
     * 异步是为了不占用微信回调线程的 5 秒预算。手动发私信没有这个约束，
     * 且前端发完要立刻刷新时间线，用这个会读不到刚发的消息——那种场景用 addWxMsgSync。
     * @param msg
     */
    void addWxMsg(WxMsg msg);

    /**
     * 同步入库，返回时消息已可查
     */
    void addWxMsgSync(WxMsg msg);

    /**
     * 单个会话的消息时间线，按 id 倒序取一页，过滤掉 event 和转多客服记录
     *
     * @param appid    公众号
     * @param openid   粉丝
     * @param beforeId 取此 id 之前的消息，翻历史用，首屏传 null
     * @param limit    条数
     * @return 倒序结果，前端自己反转
     */
    List<WxMsg> listTimeline(String appid, String openid, Long beforeId, int limit);
}

