package site.bleem.wechat.modules.wx.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import site.bleem.wechat.modules.wx.entity.WxPushBatch;

/**
 * 通用推送批次。写多读少，不缓存
 */
@Mapper
public interface WxPushBatchMapper extends BaseMapper<WxPushBatch> {
}
