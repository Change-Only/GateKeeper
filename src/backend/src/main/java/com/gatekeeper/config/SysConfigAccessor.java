package com.gatekeeper.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.SysConfig;
import com.gatekeeper.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统参数运行时读取器 —— 让「参数配置」页（{@code sys_config}）真正生效。
 *
 * <p><b>它存在的唯一理由</b>：{@code sys_config} 里的键（如 {@code sign.nonce.ttl}、
 * {@code gateway.auth.enabled}）此前只在 {@code ConfigController} 里被读写，
 * <b>全仓没有任何运行时读取点</b> —— 管理员在页面上改了半天，网关行为纹丝不动，
 * 属于「看起来能改、实际不生效」的最危险一类缺陷（尤其在应急止损时浪费排障时间）。</p>
 *
 * <p>为网关热路径（每次请求都要读）而设计，因此：</p>
 * <ol>
 *   <li><b>进程内本地缓存 + TTL</b>（{@value #CACHE_TTL_MS} ms）：避免每个请求一次 DB 往返；
 *       写侧（{@code ConfigServiceImpl}）落库后调 {@link #evictAll()} 主动失效，
 *       因此「改完立刻生效」由写后失效保证，TTL 只是兜底。</li>
 *   <li><b>FAIL-OPEN</b>：任何异常（DB 挂了、值不是数字、行缺失）一律回退到调用方传入的默认值，
 *       并只记 WARN —— 绝不因为一张配置表把整条网关链路掐死。这与本项目
 *       「新增能力 fail-open、绝不因新增依赖把既有流量打死」的一贯口径一致。</li>
 *   <li><b>失败也缓存</b>：DB 异常时同样写入一个（null）缓存项，保证「DB 故障期间最多每分钟撞一次库」，
 *       而不是每个请求都去等一次连接超时。</li>
 * </ol>
 *
 * <p>取值纪律：查询按 {@code id} 升序取<b>首个非空</b>行，刻意不用 {@code selectOne}
 * ——{@code config_key} 在 DB 层没有唯一约束（只有 uk_config_key 约定），
 * 一旦出现重复行 {@code selectOne} 会抛 {@code TooManyResultsException}（同类坑见 §15）。</p>
 *
 * @author GateKeeper
 * @since T19 (apim 配置接线)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SysConfigAccessor {

    /** 本地缓存 TTL（毫秒）——仅作写后失效的兜底，正常情况下靠 evict 立即生效 */
    static final long CACHE_TTL_MS = 60_000L;

    // ==================== 已接线的配置键（写成常量，避免散落魔法串）====================

    /** 签名算法（当前仅 SM3 合法，见 AppAuthHandler） */
    public static final String KEY_SIGN_ALGORITHM = "sign.algorithm";
    /** 时间戳容差（毫秒，默认 300000 = ±5 分钟） */
    public static final String KEY_SIGN_TIMESTAMP_TOLERANCE = "sign.timestamp.tolerance";
    /** Nonce 防重放有效期（秒，默认 600） */
    public static final String KEY_SIGN_NONCE_TTL = "sign.nonce.ttl";
    /** 是否开启网关签名校验（应急开关，关闭等于裸奔） */
    public static final String KEY_GATEWAY_AUTH_ENABLED = "gateway.auth.enabled";
    /** 是否开启网关限流 */
    public static final String KEY_GATEWAY_RATELIMIT_ENABLED = "gateway.ratelimit.enabled";
    /** 网关默认超时（毫秒，接口与环境配置都未指定时生效） */
    public static final String KEY_GATEWAY_DEFAULT_READ_TIMEOUT = "gateway.default.read.timeout";

    private final SysConfigMapper sysConfigMapper;

    /** key -> 缓存项（value 允许为 null，表示「查了但没这个键 / 查询失败」） */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /**
     * Redis 广播通道名：配置变更时向全集群广播「清空本地缓存」。
     *
     * <p>订阅方见 {@code ConfigCacheInvalidationConfig}。</p>
     */
    public static final String EVICT_CHANNEL = "gk:config:evict";

    /** 广播内容（无意义，仅用于可读性——订阅方收到任何消息都执行全清） */
    static final String EVICT_MESSAGE = "ALL";

    /**
     * 可选的跨节点失效广播器。
     *
     * <p>刻意用<b>可选注入</b>而非构造注入：单节点部署、或纯单测环境（无 Redis）下
     * 它为 null，此时 {@code evict*} 退化为「只清本节点本地缓存」——与改造前行为一致，
     * 且不影响任何既有调用方与单测的构造方式。</p>
     */
    private StringRedisTemplate redisTemplate;

    /**
     * 注入 Redis 模板（存在才注入；缺失时按单节点模式运行）。
     *
     * @param redisTemplate Redis 字符串模板
     */
    @Autowired(required = false)
    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读取字符串配置。
     *
     * @param key          配置键
     * @param defaultValue 键缺失 / 取值为空 / 查询异常时的回退值
     * @return 去掉首尾空白后的配置值；不可用时返回 defaultValue
     */
    public String getString(String key, String defaultValue) {
        String value = raw(key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    /**
     * 读取整型配置。
     *
     * @param key          配置键
     * @param defaultValue 缺失 / 非法 / 异常时的回退值
     * @return 解析成功的整数；否则 defaultValue
     */
    public int getInt(String key, int defaultValue) {
        String value = raw(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("sys_config[{}] 不是合法整数（值={}），回退默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 读取长整型配置。
     *
     * @param key          配置键
     * @param defaultValue 缺失 / 非法 / 异常时的回退值
     * @return 解析成功的长整数；否则 defaultValue
     */
    public long getLong(String key, long defaultValue) {
        String value = raw(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("sys_config[{}] 不是合法长整数（值={}），回退默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 读取布尔配置。
     *
     * <p>宽松解析：{@code true/1/yes/on} → true；{@code false/0/no/off} → false（大小写不敏感）；
     * 其他值一律回退 defaultValue 并记 WARN，避免「写了个阴阳值」被静默当成 false。</p>
     *
     * @param key          配置键
     * @param defaultValue 缺失 / 非法 / 异常时的回退值
     * @return 解析结果
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = raw(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        String v = value.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) {
            return false;
        }
        log.warn("sys_config[{}] 不是合法布尔值（值={}），回退默认值 {}", key, value, defaultValue);
        return defaultValue;
    }

    /**
     * 失效单个配置键的缓存，并广播给集群内其他节点（用于精确写后失效）。
     *
     * @param key 配置键
     */
    public void evict(String key) {
        if (key != null) {
            cache.remove(key);
        }
        broadcastEvict();
    }

    /**
     * 失效全部本地缓存 —— {@code ConfigServiceImpl} 的新建 / 更新 / 删除统一调用它，
     * 并广播给集群内其他节点。
     *
     * <p>刻意用「全清」而不是「按键清」：更新场景可能同时改到 {@code config_key}（键名变更），
     * 按键清需要同时清新旧两个键才算正确；而这张表只有十几行、重建成本可忽略，
     * 全清天然不会有漏清导致的「改了但没生效」。</p>
     *
     * <h3>为什么必须广播（集群）</h3>
     * <p>本缓存是<b>进程内</b>的。改造前 {@code evictAll} 只清调用方自己那台机器的缓存，
     * 集群里其余节点仍持有旧值、最长可达 {@value #CACHE_TTL_MS} ms —— 这与本类
     * 「改完立刻生效由写后失效保证」的设计承诺相矛盾。而这张表里装的恰恰是
     * {@code gateway.auth.enabled} / {@code gateway.ratelimit.enabled} 这类<b>应急开关</b>：
     * 运维在节点 A 上关掉限流，节点 B/C 却仍在限流，正是最需要避免的场景。</p>
     *
     * <p>广播失败不影响本地失效，其他节点由 TTL 兜底（fail-open）。</p>
     */
    public void evictAll() {
        cache.clear();
        broadcastEvict();
    }

    /**
     * 仅清空本节点本地缓存，<b>不再广播</b>。
     *
     * <p>供 Redis 订阅方在收到广播时调用。刻意与 {@link #evictAll()} 分开：
     * 若订阅方直接调 {@code evictAll()}，会在集群内形成「广播→全清→再广播」的消息回环。</p>
     */
    public void clearLocalCache() {
        cache.clear();
    }

    /**
     * 向全集群广播「配置已变更，请清空本地缓存」。
     *
     * <p>无 Redis（单节点部署）时直接跳过；异常只记 WARN，绝不向上抛——
     * 配置失效失败不应把「保存配置」这个业务动作带崩。</p>
     */
    private void broadcastEvict() {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(EVICT_CHANNEL, EVICT_MESSAGE);
        } catch (Exception e) {
            log.warn("广播配置失效失败（其他节点将由 TTL={}ms 兜底）: {}", CACHE_TTL_MS, e.getMessage());
        }
    }

    /**
     * 读原始值（带缓存 + fail-open）。
     *
     * @param key 配置键
     * @return 配置值（已 trim）；键不存在 / 值为空 / 查询异常时返回 null
     */
    private String raw(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expireAt > now) {
            return cached.value;
        }
        String value = load(key);
        // 失败（value=null）同样写缓存：DB 故障期间不放大成每请求一次的连接等待
        cache.put(key, new Entry(value, now + CACHE_TTL_MS));
        return value;
    }

    /**
     * 从 {@code sys_config} 加载单键值（不抛异常）。
     *
     * @param key 配置键
     * @return 首个非空行的 config_value（已 trim）；不可用时返回 null
     */
    private String load(String key) {
        try {
            List<SysConfig> rows = sysConfigMapper.selectList(
                    new QueryWrapper<SysConfig>().eq("config_key", key).orderByAsc("id"));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            for (SysConfig row : rows) {
                if (row != null && row.getConfigValue() != null && !row.getConfigValue().trim().isEmpty()) {
                    return row.getConfigValue().trim();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("读取 sys_config 失败（fail-open，使用调用方默认值）: key={}, cause={}", key, e.getMessage());
            return null;
        }
    }

    /** 缓存项：值 + 过期时间戳（值允许为 null） */
    private static final class Entry {
        /** 配置值（null = 查不到 / 查询失败） */
        private final String value;
        /** 过期时刻（毫秒时间戳） */
        private final long expireAt;

        private Entry(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
