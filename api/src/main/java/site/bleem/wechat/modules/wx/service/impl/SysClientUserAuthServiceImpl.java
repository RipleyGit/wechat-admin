package site.bleem.wechat.modules.wx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.common.utils.Query;
import site.bleem.wechat.modules.wx.dao.SysClientUserAuthMapper;
import site.bleem.wechat.modules.wx.entity.SysClientUserAuth;
import site.bleem.wechat.modules.wx.service.SysClientUserAuthService;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class SysClientUserAuthServiceImpl extends ServiceImpl<SysClientUserAuthMapper, SysClientUserAuth>
        implements SysClientUserAuthService {

    private static final Logger logger = LoggerFactory.getLogger(SysClientUserAuthServiceImpl.class);

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        String userId = (String) params.get("userId");
        String clientCode = (String) params.get("clientCode");
        Object statusParam = params.get("status");

        QueryWrapper<SysClientUserAuth> wrapper = new QueryWrapper<SysClientUserAuth>()
                .eq("user_type", SysClientUserAuth.USER_TYPE_WX_USER)
                .eq(StringUtils.hasText(userId), "user_id", userId)
                .eq(StringUtils.hasText(clientCode), "client_code", clientCode);
        if (statusParam != null && StringUtils.hasText(String.valueOf(statusParam))) {
            wrapper.eq("status", Integer.valueOf(String.valueOf(statusParam)));
        }

        return new PageUtils(this.page(new Query<SysClientUserAuth>().getPage(params, "id", false), wrapper));
    }

    @Override
    public List<SysClientUserAuth> listEnabled(String miniOpenid) {
        if (!StringUtils.hasText(miniOpenid)) return java.util.Collections.emptyList();
        return this.list(new QueryWrapper<SysClientUserAuth>()
                .eq("user_type", SysClientUserAuth.USER_TYPE_WX_USER)
                .eq("user_id", miniOpenid)
                .eq("status", 1));
    }

    @Override
    public boolean hasAccess(String miniOpenid, String clientCode) {
        if (!StringUtils.hasText(miniOpenid) || !StringUtils.hasText(clientCode)) return false;
        return this.count(new QueryWrapper<SysClientUserAuth>()
                .eq("user_type", SysClientUserAuth.USER_TYPE_WX_USER)
                .eq("user_id", miniOpenid)
                .eq("client_code", clientCode)
                .eq("status", 1)) > 0;
    }

    @Override
    public void touchLastLogin(String miniOpenid) {
        if (!StringUtils.hasText(miniOpenid)) return;
        try {
            this.update(new UpdateWrapper<SysClientUserAuth>()
                    .eq("user_type", SysClientUserAuth.USER_TYPE_WX_USER)
                    .eq("user_id", miniOpenid)
                    .set("last_login_at", new Date()));
        } catch (Exception e) {
            // 不影响登录主流程
            logger.warn("[用户应用授权] 更新 last_login_at 失败 miniOpenid={}", miniOpenid, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimDefault(String miniOpenid, String clientCode) {
        if (!StringUtils.hasText(miniOpenid) || !StringUtils.hasText(clientCode)) {
            throw new RRException("miniOpenid 与 clientCode 不能为空");
        }

        SysClientUserAuth existing = this.getOne(new QueryWrapper<SysClientUserAuth>()
                .eq("user_type", SysClientUserAuth.USER_TYPE_WX_USER)
                .eq("user_id", miniOpenid)
                .eq("client_code", clientCode), false);

        if (existing == null) {
            SysClientUserAuth auth = new SysClientUserAuth(miniOpenid, clientCode);
            auth.setIsDefault(true);
            this.save(auth);
        } else if (!Boolean.TRUE.equals(existing.getIsDefault())) {
            this.update(new UpdateWrapper<SysClientUserAuth>().eq("id", existing.getId()).set("is_default", true));
        }

        // 清掉该用户其他应用的默认标记，保证至多一条为默认
        this.update(new UpdateWrapper<SysClientUserAuth>()
                .eq("user_type", SysClientUserAuth.USER_TYPE_WX_USER)
                .eq("user_id", miniOpenid)
                .ne("client_code", clientCode)
                .set("is_default", false));

        logger.info("[用户应用授权] 自助开通 miniOpenid={} clientCode={}", miniOpenid, clientCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveByAdmin(SysClientUserAuth auth) {
        if (!StringUtils.hasText(auth.getUserId()) || !StringUtils.hasText(auth.getClientCode())) {
            throw new RRException("userId 与 clientCode 不能为空");
        }
        auth.setUserType(SysClientUserAuth.USER_TYPE_WX_USER);
        if (auth.getStatus() == null) auth.setStatus(1);
        if (auth.getIsDefault() == null) auth.setIsDefault(false);
        // 清空前端可能带上来的敏感字段，本期不接受外部写入
        auth.setAuthIdentifier(null);
        auth.setCredential(null);

        boolean exists = this.count(new QueryWrapper<SysClientUserAuth>()
                .eq("user_type", SysClientUserAuth.USER_TYPE_WX_USER)
                .eq("user_id", auth.getUserId())
                .eq("client_code", auth.getClientCode())) > 0;
        if (exists) {
            throw new RRException("该用户已有这个应用的授权记录");
        }

        if (Boolean.TRUE.equals(auth.getIsDefault())) {
            clearDefault(auth.getUserId());
        }
        this.save(auth);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateByAdmin(SysClientUserAuth auth) {
        if (auth.getId() == null) {
            throw new RRException("id 不能为空");
        }
        SysClientUserAuth existing = this.getById(auth.getId());
        if (existing == null) {
            throw new RRException("授权记录不存在");
        }

        // 唯一键不允许改，否则会把两条记录改成同一个人同一个应用
        auth.setUserId(null);
        auth.setUserType(null);
        auth.setClientCode(null);
        // 敏感字段本期不接受外部写入
        auth.setAuthIdentifier(null);
        auth.setCredential(null);

        if (Boolean.TRUE.equals(auth.getIsDefault())) {
            clearDefault(existing.getUserId());
        }
        this.updateById(auth);
    }

    private void clearDefault(String userId) {
        this.update(new UpdateWrapper<SysClientUserAuth>()
                .eq("user_type", SysClientUserAuth.USER_TYPE_WX_USER)
                .eq("user_id", userId)
                .set("is_default", false));
    }
}
