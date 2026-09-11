package com.gatekeeper.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT 令牌工具 — 负责管理后台登录令牌的签发与校验
 *
 * <p>使用 HS256 对称签名；密钥与有效期从配置注入（gatekeeper.jwt.*）。
 * 令牌中携带用户名与用户 ID，供鉴权拦截器识别当前登录管理员。</p>
 */
@Component
public class JwtUtil {

    /** JWT 签名密钥（配置注入，生产环境必须通过环境变量覆盖） */
    @Value("${gatekeeper.jwt.secret}")
    private String secret;

    /** 令牌有效期（分钟） */
    @Value("${gatekeeper.jwt.expire-minutes:120}")
    private long expireMinutes;

    /**
     * 为登录成功的用户签发 JWT 令牌
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @return JWT 令牌字符串
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMinutes * 60 * 1000);
        return Jwts.builder()
                .setSubject(username)
                .claim("uid", userId)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(SignatureAlgorithm.HS256, secret)
                .compact();
    }

    /**
     * 解析并校验 JWT 令牌
     *
     * @param token 令牌字符串
     * @return 解析后的 Claims；令牌无效或过期时返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .setSigningKey(secret)
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            // 签名错误 / 过期 / 格式非法均视为无效令牌
            return null;
        }
    }
}
