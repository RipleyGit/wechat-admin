package site.bleem.wechat.modules.wx.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import site.bleem.wechat.modules.wx.entity.WxMiniUser;

/**
 * 小程序用户
 *
 * 不加 @CacheNamespace：登录接口会在同一次请求里读后写（不存在则建档），
 * 二级缓存会把刚写入的行读成旧值。
 */
@Mapper
public interface WxMiniUserMapper extends BaseMapper<WxMiniUser> {
}
