package site.bleem.wechat.modules.wx.manage;

import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.common.utils.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.bean.menu.WxMenu;
import me.chanjar.weixin.common.bean.menu.WxMenuButton;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.menu.WxMpMenu;
import org.apache.commons.collections.CollectionUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 微信公众号菜单管理
 * 官方文档：https://developers.weixin.qq.com/doc/offiaccount/Custom_Menus/Creating_Custom-Defined_Menu.html
 * WxJava开发文档：https://github.com/Wechat-Group/WxJava/wiki/MP_自定义菜单管理
 */
@RestController
@RequestMapping("/manage/wxMenu")
@RequiredArgsConstructor
@Api(tags = {"公众号菜单-管理后台"})
public class WxMenuManageController {
    Logger logger = LoggerFactory.getLogger(this.getClass());
    private final WxMpService wxService;
    @Autowired
    private WxMpService wxMpService;

    /**
     * 获取公众号菜单
     */
    @GetMapping("/getMenu")
    @ApiOperation(value = "获取公众号菜单")
    public R getMenu(@CookieValue String appid) throws WxErrorException {
        wxMpService.switchoverTo(appid);
        WxMpMenu wxMpMenu = wxService.getMenuService().menuGet();
        return R.ok().put(wxMpMenu);
    }

    /**
     * 创建、更新菜单
     */
    @PostMapping("/updateMenu")
    @RequiresPermissions("wx:menu:save")
    @ApiOperation(value = "创建、更新菜单")
    public R updateMenu(@CookieValue String appid, @RequestBody WxMenu wxMenu) throws WxErrorException {
        wxMpService.switchoverTo(appid);
        if (CollectionUtils.isNotEmpty(wxMenu.getButtons())) {
            validateMiniProgramButtons(wxMenu.getButtons());
            wxService.getMenuService().menuCreate(wxMenu);
        } else {
            wxService.getMenuService().menuDelete();
        }
        return R.ok();
    }

    /**
     * 小程序按钮的 appid / pagepath / url 微信侧都是必填，缺了会在发布时才报错。
     * 在这里先拦一道，错误信息能直接指出是哪个菜单。
     */
    private void validateMiniProgramButtons(List<WxMenuButton> buttons) {
        for (WxMenuButton button : buttons) {
            if (WxConsts.MenuButtonType.MINIPROGRAM.equals(button.getType())) {
                String name = StringUtils.hasText(button.getName()) ? button.getName() : "未命名";
                if (!StringUtils.hasText(button.getAppId())) {
                    throw new RRException("菜单「" + name + "」缺少小程序 appid");
                }
                if (!StringUtils.hasText(button.getPagePath())) {
                    throw new RRException("菜单「" + name + "」缺少页面路径");
                }
                if (button.getPagePath().startsWith("/")) {
                    throw new RRException("菜单「" + name + "」的页面路径不能以 / 开头");
                }
                if (!StringUtils.hasText(button.getUrl())) {
                    throw new RRException("菜单「" + name + "」缺少兜底 url");
                }
            }
            if (CollectionUtils.isNotEmpty(button.getSubButtons())) {
                validateMiniProgramButtons(button.getSubButtons());
            }
        }
    }





}
