package site.bleem.wechat.modules.wx.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.modules.wx.service.ClientTokenSigner;

import java.util.Map;

/**
 * 子应用授权码的公钥（JWKS）。子应用按 kid 取公钥验签。
 *
 * 公开只读，没有敏感信息（只含公钥）。路径在 /wx/** 下，Shiro 匿名。
 * 完整地址：https://wechat.bleem.site/wx/.well-known/jwks.json
 * 见 docs/子应用对接门户登录授权码指南.md。
 */
@RestController
@RequestMapping("/.well-known")
@Api(tags = {"子应用授权码 JWKS"})
@RequiredArgsConstructor
public class ClientTokenJwksController {

    private final ClientTokenSigner tokenSigner;

    @GetMapping("/jwks.json")
    @ApiOperation(value = "子应用授权码验签公钥（JWKS）")
    public Map<String, Object> jwks() {
        return tokenSigner.jwks();
    }
}
