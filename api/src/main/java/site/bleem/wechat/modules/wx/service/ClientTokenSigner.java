package site.bleem.wechat.modules.wx.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import site.bleem.wechat.common.exception.RRException;

import javax.annotation.PostConstruct;
import java.text.ParseException;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * 子应用登录授权码的签发与公钥暴露。
 *
 * ES256（P-256）。私钥只在服务端配置里持有，公钥通过 JWKS 暴露给子应用验签。
 * 子应用自己拿不到私钥，伪造不了授权码。方案见
 * portal-wechat-app/docs/子应用对接门户登录授权码指南.md。
 *
 * 私钥用 JWK(JSON) 格式配在 wx.client-token.private-jwk，含私钥参数 d 与 kid。
 * 没配时签发接口直接报错（不降级成不安全的方式），JWKS 返回空集。
 * 生成一对密钥：见 docs 的「上线前准备」。
 */
@Slf4j
@Component
public class ClientTokenSigner {

    public static final String ISSUER = "wechat-admin";
    /** 授权码有效期：5 分钟，够用户从点击到子应用登录完成，又短到难以被重放 */
    private static final long TTL_SECONDS = 300;

    @Value("${wx.client-token.private-jwk:}")
    private String privateJwkJson;

    private ECKey privateJwk;
    private ECDSASigner signer;

    @PostConstruct
    public void init() {
        if (!StringUtils.hasText(privateJwkJson)) {
            log.warn("[子应用授权码] 未配置 wx.client-token.private-jwk，签发接口不可用，JWKS 为空");
            return;
        }
        try {
            ECKey key = ECKey.parse(privateJwkJson);
            if (key.getD() == null) {
                throw new IllegalArgumentException("配置的 JWK 不含私钥参数 d");
            }
            if (!Curve.P_256.equals(key.getCurve())) {
                throw new IllegalArgumentException("曲线必须是 P-256");
            }
            if (!StringUtils.hasText(key.getKeyID())) {
                throw new IllegalArgumentException("JWK 缺少 kid");
            }
            this.privateJwk = key;
            this.signer = new ECDSASigner(key);
            log.info("[子应用授权码] 签发密钥已加载 kid={}", key.getKeyID());
        } catch (Exception e) {
            // 配置错误要让人看得到，但不阻止应用启动（签发接口会在调用时报错）
            log.error("[子应用授权码] 私钥 JWK 解析失败，签发接口不可用", e);
        }
    }

    public boolean isReady() {
        return signer != null;
    }

    /**
     * 签发一个授权码。
     *
     * @param clientCode 目标应用 code，放进 aud
     * @param miniOpenid 小程序 openid，放进 sub
     * @param miniAppid  门户小程序 appid
     * @param extra      额外放进 payload 的展示字段（nickname/phone/avatar），可为 null
     */
    public Map<String, Object> issue(String clientCode, String miniOpenid, String miniAppid,
                                     Map<String, Object> extra) {
        if (!isReady()) {
            throw new RRException("子应用授权码签发未启用：服务端未配置签名私钥");
        }
        Date now = new Date();
        Date exp = new Date(now.getTime() + TTL_SECONDS * 1000);

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(clientCode)
            .subject(miniOpenid)
            .jwtID(UUID.randomUUID().toString().replace("-", ""))
            .issueTime(now)
            .expirationTime(exp)
            .claim("mini_appid", miniAppid);
        if (extra != null) {
            extra.forEach(claims::claim);
        }

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(privateJwk.getKeyID())
            .type(com.nimbusds.jose.JOSEObjectType.JWT)
            .build();

        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            log.error("[子应用授权码] 签名失败", e);
            throw new RRException("授权码签发失败");
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("token", jwt.serialize());
        result.put("expiresIn", TTL_SECONDS);
        return result;
    }

    /** 公开的 JWKS（只含公钥）。没配密钥时返回空集 */
    public Map<String, Object> jwks() {
        if (privateJwk == null) {
            return new JWKSet().toJSONObject();
        }
        try {
            return new JWKSet(privateJwk.toPublicJWK()).toJSONObject();
        } catch (Exception e) {
            log.error("[子应用授权码] 生成 JWKS 失败", e);
            return new JWKSet().toJSONObject();
        }
    }

    /** 仅供单测或排查用：解析校验一个 token（用自己的公钥），失败抛异常 */
    SignedJWT parseForTest(String token) throws ParseException {
        return SignedJWT.parse(token);
    }
}
