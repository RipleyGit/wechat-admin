package site.bleem.wechat.modules.wx.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import site.bleem.wechat.modules.wx.entity.NotifyLog;

/**
 * 消息推送记录。写多读少，不缓存
 */
@Mapper
public interface NotifyLogMapper extends BaseMapper<NotifyLog> {
}
