package site.bleem.wechat.modules.wx.manage;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.WxMiniUser;
import site.bleem.wechat.modules.wx.service.WxMiniUserService;

import java.util.Map;

/**
 * 小程序用户-管理后台
 *
 * 权限码 wx:wxminiuser:* 由 db/migrations/20261025_add_wx_mini_user.sql 种进
 * sys_menu，不种则连超级管理员也是 403。
 */
@RestController
@RequestMapping("/manage/wxMiniUser")
@Api(tags = {"小程序用户-管理后台"})
public class WxMiniUserManageController {

    @Autowired
    private WxMiniUserService wxMiniUserService;

    @GetMapping("/list")
    @RequiresPermissions("wx:wxminiuser:list")
    @ApiOperation(value = "列表")
    public R list(@RequestParam Map<String, Object> params) {
        return R.ok().put("page", wxMiniUserService.queryPage(params));
    }

    @GetMapping("/info/{id}")
    @RequiresPermissions("wx:wxminiuser:info")
    @ApiOperation(value = "详情")
    public R info(@PathVariable("id") Long id) {
        return R.ok().put(wxMiniUserService.getById(id));
    }

    /**
     * 修改用户。**这里是应用可见范围（appCodes）与默认应用（defaultCode）的
     * 唯一入口** —— 小程序端的资料表单只能改昵称/手机号/备注。
     *
     * 没有「新增」接口：档案只由小程序登录接口自动建档，后台手动造一条既没有
     * 真实 openid 可填，也会和后续自动建档撞唯一键。
     */
    @PutMapping("/update")
    @RequiresPermissions("wx:wxminiuser:update")
    @ApiOperation(value = "修改")
    public R update(@RequestBody WxMiniUser user) {
        wxMiniUserService.updateByAdmin(user);
        return R.ok();
    }

    @DeleteMapping("/delete")
    @RequiresPermissions("wx:wxminiuser:delete")
    @ApiOperation(value = "删除")
    public R delete(@RequestBody Long[] ids) {
        wxMiniUserService.removeByIds(java.util.Arrays.asList(ids));
        return R.ok();
    }

    /** 手动触发一次服务号用户关联，供后台在补了手机号/unionid 后使用 */
    @PostMapping("/link/{id}")
    @RequiresPermissions("wx:wxminiuser:update")
    @ApiOperation(value = "关联服务号用户")
    public R link(@PathVariable("id") Long id) {
        WxMiniUser user = wxMiniUserService.getById(id);
        if (user == null) {
            return R.error("用户不存在");
        }
        if (!wxMiniUserService.linkServiceUser(user)) {
            return R.error("没找到匹配的服务号用户（需要 unionid 或唯一的手机号）");
        }
        wxMiniUserService.updateById(user);
        return R.ok().put(user);
    }
}
