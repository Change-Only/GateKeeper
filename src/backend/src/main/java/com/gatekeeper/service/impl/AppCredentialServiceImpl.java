package com.gatekeeper.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.Result;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.dto.AppCredentialDto;
import com.gatekeeper.dto.CredentialRotateRequest;
import com.gatekeeper.entity.AppCredential;
import com.gatekeeper.entity.Env;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.AppCredentialMapper;
import com.gatekeeper.service.AppCredentialService;
import com.gatekeeper.service.CredentialFacadeService;
import com.gatekeeper.service.EnvService;
import com.gatekeeper.util.CryptoKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用凭证服务实现 — T03a 三大基础域中最复杂的一个
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li>appKey 生成：{@code ak_{envCode}_{16位随机}}</li>
 *   <li>appSecret 生成：{@code UUID+UUID}（64 hex）+ AES-256 ECB 加密落库</li>
 *   <li>轮换窗口：原主密钥 alias 改为「-旧(将于 N 天后吊销)」+ expireTime=now+N，
 *       同时新建 rotateFlag=1 凭证</li>
 *   <li>安全：所有非 create/rotate 接口响应绝不返回明文 secret</li>
 * </ul></p>
 *
 * <h3>状态字典</h3>
 * <ul>
 *   <li>status: 0=未分配, 1=启用中, 2=已停用, 3=已吊销, 4=已过期</li>
 *   <li>rotateFlag: 0=主密钥, 1=轮换中</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppCredentialServiceImpl extends ServiceImpl<AppCredentialMapper, AppCredential>
        implements AppCredentialService, CredentialFacadeService {

    private final EnvService envService;

    /** AppSecret 落库 AES-256 ECB 密钥（与存量 app.app_secret 共用） */
    @Value("${gatekeeper.crypto.aes-key}")
    private String aesDbKey;

    private final CryptoService cryptoService;

    /** 默认轮换过渡天数 */
    private static final int DEFAULT_EXPIRE_DAYS = 7;

    /** 轮换过渡天数上限 */
    private static final int MAX_EXPIRE_DAYS = 30;

    // =====================================================================
    // T03a 暴露给 Controller 的服务
    // =====================================================================

    @Override
    public List<AppCredentialDto> list(Long appId, String envCode, Integer status) {
        QueryWrapper<AppCredential> wrapper = new QueryWrapper<>();
        if (appId != null) {
            wrapper.eq("app_id", appId);
        }
        if (StringUtils.hasText(envCode)) {
            wrapper.eq("env_code", envCode);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        List<AppCredential> rows = baseMapper.selectList(wrapper);
        List<AppCredentialDto> result = new ArrayList<>(rows.size());
        for (AppCredential c : rows) {
            result.add(toDto(c, false));
        }
        return result;
    }

    @Override
    public AppCredentialDto get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("凭证ID不能为空");
        }
        AppCredential c = baseMapper.selectById(id);
        if (c == null) {
            throw GatewayException.notFound("凭证不存在: id=" + id);
        }
        return toDto(c, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppCredentialDto create(AppCredentialDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getAppId() == null) {
            throw GatewayException.badRequest("应用ID不能为空");
        }
        String envCode = dto.getEnvCode();
        if (!StringUtils.hasText(envCode)) {
            throw GatewayException.badRequest("环境编码不能为空");
        }
        // 校验环境存在（避免悬空 env_code）
        Env env = envService.getByEnvCode(envCode);
        if (env == null) {
            throw GatewayException.badRequest("环境不存在: envCode=" + envCode);
        }

        AppCredential entity = new AppCredential();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(null);

        // 生成 appKey：ak_{envCode}_{16位随机}
        String appKey = generateAppKey(envCode);
        entity.setAppKey(appKey);

        // 生成 appSecret 并加密落库
        String plainSecret = generateAppSecret();
        entity.setAppSecret(encryptSecret(plainSecret));
        entity.setSecretMask(toSecretMask(plainSecret));

        entity.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        entity.setRotateFlag(0); // 直接创建即主密钥
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        if (!StringUtils.hasText(entity.getAlias())) {
            entity.setAlias("主密钥");
        }
        if (!StringUtils.hasText(dto.getCreatedBy())) {
            entity.setCreatedBy("system");
        }
        baseMapper.insert(entity);

        AppCredentialDto result = toDto(entity, true);
        result.setAppSecret(plainSecret); // 仅本次返回明文
        log.info("AppCredential created: id={}, appId={}, envCode={}, appKey={}",
                entity.getId(), entity.getAppId(), entity.getEnvCode(), entity.getAppKey());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppCredentialDto rotate(CredentialRotateRequest req) {
        if (req == null || req.getAppId() == null || !StringUtils.hasText(req.getEnvCode())) {
            throw GatewayException.badRequest("轮换请求参数不完整（appId/envCode 必填）");
        }
        Long appId = req.getAppId();
        String envCode = req.getEnvCode();

        // 1) 若已有轮换中凭证 → 拒绝（避免多窗口轮换叠加）
        AppCredential rotating = baseMapper.selectOne(
                new QueryWrapper<AppCredential>()
                        .eq("app_id", appId)
                        .eq("env_code", envCode)
                        .eq("rotate_flag", 1));
        if (rotating != null) {
            throw GatewayException.badRequest("该应用在此环境下已存在轮换中凭证，请先完成或撤销现有轮换");
        }

        // 2) 取当前主密钥
        AppCredential primary = baseMapper.selectOne(
                new QueryWrapper<AppCredential>()
                        .eq("app_id", appId)
                        .eq("env_code", envCode)
                        .eq("rotate_flag", 0));
        if (primary == null) {
            throw GatewayException.badRequest("该应用在此环境下尚无主密钥，请使用「创建凭证」接口");
        }

        // 3) 计算过期天数（默认 7，最多 30）
        int days = DEFAULT_EXPIRE_DAYS;
        if (req.getExpireAfterDays() != null) {
            if (req.getExpireAfterDays() < 1 || req.getExpireAfterDays() > MAX_EXPIRE_DAYS) {
                days = DEFAULT_EXPIRE_DAYS;
            } else {
                days = req.getExpireAfterDays();
            }
        }

        // 4) 把原主密钥改成「即将吊销」：alias 改名 + expireTime=now+days
        String oldAlias = primary.getAlias() == null ? "主密钥" : primary.getAlias();
        primary.setAlias(oldAlias + "-旧(将于 " + days + " 天后吊销)");
        primary.setExpireTime(LocalDateTime.now().plusDays(days));
        primary.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(primary);

        // 5) 新建轮换中凭证
        AppCredential newCred = new AppCredential();
        newCred.setAppId(appId);
        newCred.setEnvCode(envCode);
        String newAlias = (StringUtils.hasText(req.getNewAlias()) ? req.getNewAlias()
                : oldAlias + "-轮换中(新)");
        newCred.setAlias(newAlias);
        newCred.setAppKey(generateAppKey(envCode));
        String plainSecret = generateAppSecret();
        newCred.setAppSecret(encryptSecret(plainSecret));
        newCred.setSecretMask(toSecretMask(plainSecret));
        newCred.setStatus(1); // 启用中
        newCred.setRotateFlag(1); // 轮换中
        newCred.setCreateTime(LocalDateTime.now());
        newCred.setUpdatedAt(LocalDateTime.now());
        newCred.setCreatedBy(StringUtils.hasText(req.getOperatorName()) ? req.getOperatorName() : "system");
        baseMapper.insert(newCred);

        AppCredentialDto result = toDto(newCred, true);
        result.setAppSecret(plainSecret); // 仅本次返回明文
        log.info("AppCredential rotated: appId={}, envCode={}, oldId={}, newId={}",
                appId, envCode, primary.getId(), newCred.getId());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int completeRotate(Long appId, String envCode) {
        if (appId == null || !StringUtils.hasText(envCode)) {
            throw GatewayException.badRequest("appId / envCode 不能为空");
        }
        // 1) 取轮换中凭证
        AppCredential rotating = baseMapper.selectOne(
                new QueryWrapper<AppCredential>()
                        .eq("app_id", appId)
                        .eq("env_code", envCode)
                        .eq("rotate_flag", 1));
        if (rotating == null) {
            throw GatewayException.badRequest("该应用在此环境下不存在轮换中凭证");
        }
        // 2) 取旧主密钥（即将吊销的）
        AppCredential oldPrimary = baseMapper.selectOne(
                new QueryWrapper<AppCredential>()
                        .eq("app_id", appId)
                        .eq("env_code", envCode)
                        .eq("rotate_flag", 0));
        if (oldPrimary == null) {
            throw GatewayException.badRequest("该应用在此环境下不存在主密钥");
        }
        // 3) 旧主密钥：status=3（已吊销），同时把 alias 收尾去掉「即将吊销」后缀的痕迹
        oldPrimary.setStatus(3);
        oldPrimary.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(oldPrimary);

        // 4) 轮换中凭证：rotateFlag=0（晋升主密钥），alias 恢复简化命名
        rotating.setRotateFlag(0);
        // 把 alias 中的"-轮换中(新)"去掉，还原为简洁名
        String simplifiedAlias = rotating.getAlias() == null ? "主密钥"
                : rotating.getAlias().replace("-轮换中(新)", "").trim();
        if (!StringUtils.hasText(simplifiedAlias)) {
            simplifiedAlias = "主密钥";
        }
        rotating.setAlias(simplifiedAlias);
        rotating.setExpireTime(null); // 解除旧主密钥带来的过期时间（独立凭证，无过期）
        rotating.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(rotating);

        log.info("AppCredential completeRotate: appId={}, envCode={}, oldId={}, newPrimaryId={}",
                appId, envCode, oldPrimary.getId(), rotating.getId());
        return 2;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("凭证ID不能为空");
        }
        AppCredential c = baseMapper.selectById(id);
        if (c == null) {
            throw GatewayException.notFound("凭证不存在: id=" + id);
        }
        if (c.getStatus() != null && c.getStatus() == 3) {
            log.warn("AppCredential already revoked: id={}", id);
            return;
        }
        // 吊销：status=3，保留 rotateFlag（若是主密钥，则整个应用在此 env 的轮换身份需要重新 rotate）
        c.setStatus(3);
        c.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(c);
        log.info("AppCredential revoked: id={}, appId={}, envCode={}",
                id, c.getAppId(), c.getEnvCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AppCredentialDto dto) {
        if (id == null) {
            throw GatewayException.badRequest("凭证ID不能为空");
        }
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        AppCredential existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("凭证不存在: id=" + id);
        }
        // update 仅允许修改 alias / expireTime，密钥本身不允许在此被改
        if (StringUtils.hasText(dto.getAlias())) {
            existing.setAlias(dto.getAlias());
        }
        if (dto.getExpireTime() != null) {
            existing.setExpireTime(dto.getExpireTime());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("AppCredential updated: id={}", id);
    }

    // =====================================================================
    // CredentialFacadeService（T03a 仅暴露，T04 由 AppAuthHandler 启用）
    // =====================================================================

    @Override
    public AppCredential getActiveCredential(Long appId, String envCode) {
        if (appId == null || !StringUtils.hasText(envCode)) {
            return null;
        }
        // 优先级 1：主密钥（rotateFlag=0, status=1）
        AppCredential primary = baseMapper.selectOne(
                new QueryWrapper<AppCredential>()
                        .eq("app_id", appId)
                        .eq("env_code", envCode)
                        .eq("rotate_flag", 0)
                        .eq("status", 1));
        if (primary != null) {
            return primary;
        }
        // 优先级 2：轮换中凭证（rotateFlag=1, status=1）
        return baseMapper.selectOne(
                new QueryWrapper<AppCredential>()
                        .eq("app_id", appId)
                        .eq("env_code", envCode)
                        .eq("rotate_flag", 1)
                        .eq("status", 1));
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    /**
     * 生成 appKey：{@code ak_{envCode}_{16位随机}}
     */
    private String generateAppKey(String envCode) {
        // 取 UUID 前 16 hex 字符作为唯一标识
        String uuid = IdUtil.fastSimpleUUID();
        String tail = uuid.replace("-", "").substring(0, 16);
        return "ak_" + envCode + "_" + tail;
    }

    /**
     * 生成 appSecret 明文（64 hex）。
     */
    private String generateAppSecret() {
        // 双 UUID 拼接 → 64 hex
        String uuid = IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID();
        String hex = uuid.replace("-", "");
        if (hex.length() > 64) {
            hex = hex.substring(0, 64);
        } else if (hex.length() < 64) {
            // 极端情况补齐
            while (hex.length() < 64) {
                hex = hex + "0";
            }
        }
        return hex;
    }

    /**
     * AES-256 ECB 加密（与存量 app.app_secret 共用一套密钥）。
     */
    private String encryptSecret(String plainSecret) {
        try {
            String key = CryptoKeyUtil.toBase64Key(aesDbKey);
            return cryptoService.encrypt("AES", plainSecret, key, null, "ECB", "PKCS5Padding");
        } catch (Exception e) {
            // 加密失败：抛业务异常（FAIL-CLOSED，避免明文落库）
            log.error("Encrypt appSecret failed: {}", e.getMessage(), e);
            throw GatewayException.badGateway("凭证加密失败");
        }
    }

    /**
     * 生成密钥掩码（首 4 + **** + 末 4）。明文长度不足 8 时回退 "****"。
     */
    private String toSecretMask(String plainSecret) {
        if (plainSecret == null || plainSecret.length() < 8) {
            return "****";
        }
        return plainSecret.substring(0, 4) + "****" + plainSecret.substring(plainSecret.length() - 4);
    }

    /**
     * Entity → DTO。{@code withSecret} 仅在 create / rotate 的「明文返回窗口」传 true。
     */
    private AppCredentialDto toDto(AppCredential c, boolean withSecret) {
        if (c == null) {
            return null;
        }
        AppCredentialDto dto = new AppCredentialDto();
        BeanUtils.copyProperties(c, dto);
        // 强制抹掉明文（防御性的兜底，避免某些路径漏传 withSecret 时泄露）
        if (!withSecret) {
            dto.setAppSecret(null);
        }
        // 兼容 exp 是 LocalDate / LocalDateTime 之间的坑（DTO 字段为 LocalDateTime，entity 也是）
        return dto;
    }
}
