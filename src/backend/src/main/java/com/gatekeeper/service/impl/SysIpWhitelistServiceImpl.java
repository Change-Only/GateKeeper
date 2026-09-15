package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gatekeeper.entity.SysIpWhitelist;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.SysIpWhitelistMapper;
import com.gatekeeper.service.SysIpWhitelistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 系统级访问白名单服务实现 — T15-4
 *
 * <p><b>本实现最要紧的一件事是「写前把 IP/CIDR 校验死」</b>。
 * 白名单是强语义配置（要么放行、要么拦死），一个拼错的 CIDR
 * （例如把 {@code 10.0.0.0/24} 写成 {@code 10.0.0.0-24}、或前缀写成 {@code /33}）
 * 在运行期只是"永远匹配不上"——**没有任何报错**，用户会以为加好了，
 * 直到发现某个本该公司内网访问的来源被拒（或反之以为已限制其实没限制）。
 * 因此新增/编辑都做格式 + 前缀范围校验，并把错误语义说清楚。</p>
 *
 * <p><b>更新用 {@link UpdateWrapper} 显式 set，而不是 {@code updateById}</b>：
 * MyBatis-Plus 的 {@code updateById} 走 NOT_NULL 策略，null 字段不写库
 * ⇒「清空备注」这类操作会静默失效，留下"看着删了其实还在"的幽灵值。
 * 显式 {@code set(...)} 才能把 null 真正写进库。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysIpWhitelistServiceImpl implements SysIpWhitelistService {

    /** IPv4 点分十进制字面量（逐段再校验 ≤255） */
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final SysIpWhitelistMapper mapper;

    @Override
    public List<SysIpWhitelist> list() {
        List<SysIpWhitelist> rows = mapper.selectList(
                new QueryWrapper<SysIpWhitelist>().orderByDesc("status").orderByDesc("created_at"));
        return rows == null ? Collections.emptyList() : rows;
    }

    @Override
    public List<SysIpWhitelist> listEnabled() {
        List<SysIpWhitelist> rows = mapper.selectList(
                new QueryWrapper<SysIpWhitelist>().eq("status", SysIpWhitelist.STATUS_ENABLED));
        return rows == null ? Collections.emptyList() : rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(SysIpWhitelist entry) {
        if (entry == null) {
            throw GatewayException.badRequest("白名单内容不能为空");
        }
        String cidr = normalizeCidr(entry.getIpCidr());
        assertCidrValid(cidr);
        assertNotDuplicated(cidr, null);

        entry.setId(null);
        entry.setIpCidr(cidr);
        entry.setStatus(entry.getStatus() == null ? SysIpWhitelist.STATUS_ENABLED : entry.getStatus());
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());
        if (!StringUtils.hasText(entry.getRemark())) {
            entry.setRemark(null);
        }
        mapper.insert(entry);
        log.info("Sys ip whitelist added: id={}, cidr={}, status={}", entry.getId(), cidr, entry.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateById(Long id, SysIpWhitelist entry) {
        if (id == null) {
            throw GatewayException.badRequest("白名单ID不能为空");
        }
        SysIpWhitelist existing = mapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("系统访问白名单不存在");
        }
        if (entry == null) {
            throw GatewayException.badRequest("白名单内容不能为空");
        }
        String cidr = normalizeCidr(entry.getIpCidr());
        assertCidrValid(cidr);
        assertNotDuplicated(cidr, id);

        int status = entry.getStatus() == null ? SysIpWhitelist.STATUS_ENABLED : entry.getStatus();
        String remark = StringUtils.hasText(entry.getRemark()) ? entry.getRemark().trim() : null;

        // 显式 set：允许把 remark 写回 null（updateById 的 NOT_NULL 策略做不到）
        mapper.update(null, new UpdateWrapper<SysIpWhitelist>()
                .eq("id", id)
                .set("ip_cidr", cidr)
                .set("remark", remark)
                .set("status", status)
                .set("updated_at", LocalDateTime.now()));
        log.info("Sys ip whitelist updated: id={}, cidr={}, status={}", id, cidr, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("白名单ID不能为空");
        }
        SysIpWhitelist existing = mapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("系统访问白名单不存在");
        }
        mapper.deleteById(id);
        log.info("Sys ip whitelist deleted: id={}, cidr={}", id, existing.getIpCidr());
    }

    // =====================================================================
    // 内部：归一与校验
    // =====================================================================

    private String normalizeCidr(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw GatewayException.badRequest("IP / CIDR 不能为空");
        }
        return raw.trim().replaceAll("\\s+", "");
    }

    /**
     * 校验 IP / CIDR 字面量。
     *
     * <p>刻意不用 {@link InetAddress#getByName} 单独完成校验 —— 它会把
     * {@code localhost} 这类**主机名**也解析成地址从而通过校验，
     * 而白名单里存主机名在运行期无法与来源 IP 匹配（等于配了个永不生效的规则）。
     * 故先用字面量形态判断，再用 InetAddress 兜住 IPv6 的字节合法性。</p>
     */
    private void assertCidrValid(String cidr) {
        String host = cidr;
        Integer prefix = null;
        int slash = cidr.indexOf('/');
        if (slash >= 0) {
            host = cidr.substring(0, slash);
            String rawPrefix = cidr.substring(slash + 1);
            if (rawPrefix.isEmpty()) {
                throw GatewayException.badRequest("CIDR 前缀不能为空，正确写法如 10.0.0.0/24");
            }
            try {
                prefix = Integer.valueOf(rawPrefix);
            } catch (NumberFormatException e) {
                throw GatewayException.badRequest("CIDR 前缀必须是数字，正确写法如 10.0.0.0/24，当前为：" + cidr);
            }
        }
        if (host.isEmpty()) {
            throw GatewayException.badRequest("IP / CIDR 格式不正确：" + cidr);
        }

        byte[] addr;
        boolean ipv6 = host.indexOf(':') >= 0;
        if (!ipv6) {
            if (!IPV4.matcher(host).matches()) {
                throw GatewayException.badRequest("IPv4 格式不正确：" + host + "（请填写如 10.0.0.1 或 10.0.0.0/24）");
            }
            for (String seg : host.split("\\.")) {
                if (Integer.parseInt(seg) > 255) {
                    throw GatewayException.badRequest("IPv4 段值超出 0-255：" + host);
                }
            }
        }
        try {
            addr = InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            throw GatewayException.badRequest("IP / CIDR 格式不正确：" + cidr);
        }
        int maxPrefix = addr.length == 4 ? 32 : 128;
        if (prefix != null && (prefix < 0 || prefix > maxPrefix)) {
            throw GatewayException.badRequest("CIDR 前缀超出范围（应为 0-" + maxPrefix + "）：" + cidr);
        }
    }

    /** 唯一键 uk_syswl_cidr：提前给出可读错误，避免依赖 DB 抛 DuplicateKeyException 让用户看到 500。 */
    private void assertNotDuplicated(String cidr, Long selfId) {
        List<SysIpWhitelist> same = mapper.selectList(
                new QueryWrapper<SysIpWhitelist>().eq("ip_cidr", cidr));
        if (same == null) {
            return;
        }
        for (SysIpWhitelist row : same) {
            if (selfId == null || !selfId.equals(row.getId())) {
                throw GatewayException.badRequest("该 IP / CIDR 已存在：" + cidr);
            }
        }
    }
}
