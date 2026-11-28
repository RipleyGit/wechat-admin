package site.bleem.wechat.modules.wx.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.Mapper;
import site.bleem.wechat.modules.wx.entity.SysClientUserAuth;

@Mapper
@CacheNamespace(flushInterval = 300000L)// 缓存五分钟过期
public interface SysClientUserAuthMapper extends BaseMapper<SysClientUserAuth> {
}
