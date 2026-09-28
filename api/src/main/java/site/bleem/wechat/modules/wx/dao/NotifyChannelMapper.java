package site.bleem.wechat.modules.wx.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import site.bleem.wechat.modules.wx.entity.NotifyChannel;

/**
 * 消息推送通道
 *
 * 不加 @CacheNamespace：通道是网关每次请求都要读的配置，页面上改完得立刻生效，
 * 缓存五分钟会让人以为改动没保存。
 */
@Mapper
public interface NotifyChannelMapper extends BaseMapper<NotifyChannel> {
}
