package site.bleem.wechat.modules.wx.service;

import com.baomidou.mybatisplus.extension.service.IService;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.modules.wx.entity.SysClientUserAuth;

import java.util.List;
import java.util.Map;

/**
 * 应用认证用户：谁能用哪个子应用。方案见
 * portal-wechat-app/docs/小程序用户应用授权重构方案.md。
 */
public interface SysClientUserAuthService extends IService<SysClientUserAuth> {

    /** 后台列表分页，按用户/应用/状态筛选。每行附带用户昵称、手机号（来自 wx_user） */
    PageUtils queryPage(Map<String, Object> params);

    /**
     * 可授权的小程序用户：已关联小程序（mini_openid 非空）的 wx_user，
     * 新增授权时下拉选用。返回 openid / 昵称 / 手机号。
     */
    java.util.List<java.util.Map<String, Object>> listGrantableUsers(String keyword);

    /**
     * 查某个小程序用户 status=1 的授权列表，登录接口用来组装 appCodes / defaultCode。
     * 已停用的不返回。
     */
    List<SysClientUserAuth> listEnabled(String miniOpenid);

    /** 判断某用户是否有权用某应用（status=1），clientToken 签发前的授权检查 */
    boolean hasAccess(String miniOpenid, String clientCode);

    /**
     * 更新某用户所有授权行的 last_login_at。登录成功时调用，失败不影响登录流程。
     */
    void touchLastLogin(String miniOpenid);

    /**
     * 自助开通：启动链接 ?defaultCode=app_xxx 的落地点。
     * 已有该应用的行只设默认，不改 status —— 被管理员停用的应用不会因为
     * 点链接自动恢复。清掉该用户其他行的 is_default 后设置这一行，同一事务内。
     */
    void claimDefault(String miniOpenid, String clientCode);

    /** 后台新增一条授权 */
    void saveByAdmin(SysClientUserAuth auth);

    /**
     * 后台修改。唯一键（userType/userId/clientCode）不可改，否则会把两条
     * 记录改成同一个人同一个应用。is_default=true 时，同一事务内先清掉该
     * 用户其他行的默认标记。
     */
    void updateByAdmin(SysClientUserAuth auth);
}
