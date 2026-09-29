package site.bleem.wechat.modules.wx.service;

import com.baomidou.mybatisplus.extension.service.IService;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.modules.wx.entity.WxAccount;

import java.util.Collection;
import java.util.Map;

/**
 * 公众号账号
 *
 * @author niefy
 * @date 2020-06-17 13:56:51
 */
public interface WxAccountService extends IService<WxAccount> {
    /**
     * 分页查询用户数据
     * @param params 查询参数
     * @return PageUtils 分页结果
     */
    PageUtils queryPage(Map<String, Object> params);

    void loadWxMpConfigStorages();

    boolean saveOrUpdateWxAccount(WxAccount entity);

    /**
     * 按推送密钥找公众号，找不到返回 null
     */
    WxAccount getByNotifySecret(String notifySecret);

    /**
     * 生成新的推送密钥并写库，旧密钥立即失效
     * @return 新密钥明文
     */
    String regenerateNotifySecret(String appid);

    @Override
    boolean removeByIds(Collection<?> idList);
}

