package site.bleem.wechat.modules.wx.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import site.bleem.wechat.modules.wx.entity.WxMsgRead;

/**
 * 已读水位线。每个运营账号对每个会话各记一条。
 *
 * 同样不加 @CacheNamespace：这张表的值直接决定未读数，缓存 10 秒就是红点消不掉。
 */
@Mapper
public interface WxMsgReadMapper extends BaseMapper<WxMsgRead> {

    /**
     * 抬高已读水位线到指定消息 id
     *
     * 只增不减：并发下两个标记已读请求乱序到达时，小的那个不能把水位线拉回去。
     */
    int upsertLastReadMsgId(@Param("appid") String appid,
                           @Param("openid") String openid,
                           @Param("userId") Long userId,
                           @Param("lastReadMsgId") Long lastReadMsgId);
}
