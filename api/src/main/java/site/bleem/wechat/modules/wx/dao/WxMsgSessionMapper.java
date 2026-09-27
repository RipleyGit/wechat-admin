package site.bleem.wechat.modules.wx.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import site.bleem.wechat.modules.wx.dto.WxMsgSessionView;
import site.bleem.wechat.modules.wx.entity.WxMsgSession;

import java.util.Date;
import java.util.List;

/**
 * 私信会话
 *
 * 故意不加 @CacheNamespace：本 mapper 的查询要 join wx_msg，而 MyBatis 二级缓存只在同 namespace
 * 有写操作时失效。WxMsgMapper 插入新消息不会清本 namespace 的缓存，加了缓存就等于未读数不准。
 */
@Mapper
public interface WxMsgSessionMapper extends BaseMapper<WxMsgSession> {

    /**
     * 会话列表，带昵称头像和当前运营的未读数
     *
     * @param appid  公众号
     * @param userId 当前运营账号，决定未读数
     * @param offset 偏移
     * @param limit  条数
     */
    List<WxMsgSessionView> selectSessionViews(@Param("appid") String appid,
                                             @Param("userId") Long userId,
                                             @Param("keyword") String keyword,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    /**
     * 会话总数，分页用
     */
    long countSessions(@Param("appid") String appid, @Param("keyword") String keyword);

    /**
     * 有未读消息的会话数，全局红点用
     */
    int countUnreadSessions(@Param("appid") String appid, @Param("userId") Long userId);

    /**
     * 更新最后一条可展示消息（摘要、时间、hasRealMsg）
     *
     * addWxMsg 是 @Async 的，多条消息可能乱序落库，所以带时间守卫：
     * 只在传入时间不早于已存值时才覆盖摘要。
     */
    int upsertLastMsg(@Param("appid") String appid,
                      @Param("openid") String openid,
                      @Param("msgTime") Date msgTime,
                      @Param("summary") String summary);

    /**
     * 更新最后一次入站交互时间（含 event，48h 窗口依据）
     */
    int upsertLastInTime(@Param("appid") String appid,
                         @Param("openid") String openid,
                         @Param("inTime") Date inTime);
}
