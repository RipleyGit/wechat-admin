package site.bleem.wechat.modules.wx.manage;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.SysClientUserAuth;
import site.bleem.wechat.modules.wx.service.SysClientUserAuthService;

import java.util.Arrays;
import java.util.Map;

/**
 * 用户应用管理-管理后台。
 *
 * 一行一条授权记录（用户 × 应用）。权限码 wx:clientuserauth:* 由
 * db/migrations/20261128_sys_client_user_auth.sql 种进 sys_menu。
 * 方案见 portal-wechat-app/docs/小程序用户应用授权重构方案.md。
 */
@RestController
@RequestMapping("/manage/sysClientUserAuth")
@Api(tags = {"用户应用管理-管理后台"})
@RequiredArgsConstructor
public class SysClientUserAuthManageController {

    private final SysClientUserAuthService authService;

    @GetMapping("/list")
    @RequiresPermissions("wx:clientuserauth:list")
    @ApiOperation(value = "列表")
    public R list(@RequestParam Map<String, Object> params) {
        return R.ok().put(authService.queryPage(params));
    }

    @GetMapping("/info/{id}")
    @RequiresPermissions("wx:clientuserauth:info")
    @ApiOperation(value = "详情")
    public R info(@PathVariable("id") Long id) {
        return R.ok().put(authService.getById(id));
    }

    /** 新增授权时可选的小程序用户：已关联小程序的粉丝，带昵称、手机号 */
    @GetMapping("/grantableUsers")
    @RequiresPermissions("wx:clientuserauth:list")
    @ApiOperation(value = "可授权的小程序用户下拉")
    public R grantableUsers(@RequestParam(required = false) String keyword) {
        return R.ok().put(authService.listGrantableUsers(keyword));
    }

    @PostMapping("/save")
    @RequiresPermissions("wx:clientuserauth:save")
    @ApiOperation(value = "新增")
    public R save(@RequestBody SysClientUserAuth auth) {
        authService.saveByAdmin(auth);
        return R.ok();
    }

    @PutMapping("/update")
    @RequiresPermissions("wx:clientuserauth:update")
    @ApiOperation(value = "修改")
    public R update(@RequestBody SysClientUserAuth auth) {
        authService.updateByAdmin(auth);
        return R.ok();
    }

    @DeleteMapping("/delete")
    @RequiresPermissions("wx:clientuserauth:delete")
    @ApiOperation(value = "删除")
    public R delete(@RequestBody Long[] ids) {
        authService.removeByIds(Arrays.asList(ids));
        return R.ok();
    }
}
