package site.bleem.wechat.modules.wx.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import site.bleem.wechat.modules.wx.entity.WxMiniUser;
import site.bleem.wechat.modules.wx.form.WxMiniUserLoginForm;
import site.bleem.wechat.modules.wx.form.WxMiniUserProfileForm;

import java.util.Map;

public interface WxMiniUserService extends IService<WxMiniUser> {

    IPage<WxMiniUser> queryPage(Map<String, Object> params);

    /** 按业务唯一键取档案，不存在返回 null */
    WxMiniUser getByMiniOpenid(String miniAppid, String miniOpenid);

    /**
     * 小程序登录：查档案，不存在则建档。
     *
     * 建档后会尝试按 unionid / 手机号回填 serviceOpenid（关联服务号用户）。
     *
     * @return 档案，永不为 null
     */
    WxMiniUser login(WxMiniUserLoginForm form);

    /** 用户自己完善资料。只允许改表单里那几个字段，按唯一键定位 */
    WxMiniUser updateProfile(WxMiniUserProfileForm form);

    /**
     * 按 unionid 或手机号找同一个人的服务号 openid 并回填。
     *
     * @return 是否回填成功
     */
    boolean linkServiceUser(WxMiniUser user);
}
