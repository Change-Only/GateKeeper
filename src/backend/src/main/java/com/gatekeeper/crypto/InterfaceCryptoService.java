package com.gatekeeper.crypto;

import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;

/**
 * 接口信息字段级加解密服务 — T17
 *
 * <h3>管什么（一句话）</h3>
 * <p>把 GateKeeper <b>自身数据</b>里承载「接口契约内容」的字段做<b>存储加密</b>，
 * 并按可见性白名单决定控制台回显明文还是掩码。
 * 这是用户口径「别人在使用这个系统的时候从控制台看不到我的接口信息」的落点。
 * <b>与网关对外报文加解密（T15/T16 三档配置）没有任何关系</b>，勿混。</p>
 *
 * <h3>保护对象</h3>
 * <ul>
 *   <li>{@code api_interface.interface_path} —— 网关对外暴露路径</li>
 *   <li>{@code api_param.field_name} / {@code example} / {@code description}
 *       —— 参数契约内容（列名/示例值/说明）</li>
 *   <li><b>刻意不加密</b>：{@code backend_url}（后端真实地址，用户口径未纳入）、
 *       {@code interface_name} 等名称类业务信息、以及参数的<b>结构列</b>
 *       （param_type / field_type / required / error_code / http_status /
 *       sensitive / encrypt_rule / sort_order）。结构列不泄漏契约本身，
 *       且被渲染与校验逻辑直接消费；全加密只会让参数页对非白名单用户彻底空白。</li>
 * </ul>
 *
 * <h3>🔴 盲索引（blind index）—— 本设计的关键</h3>
 * <p>{@code interface_path} 是<b>网关的路由依据</b>：{@code PermissionHandler} 按候选路径
 * {@code eq("interface_path", candidate)} 直接查库。若该列整体改为随机 IV 密文，
 * 等值查询永远匹配不上 ⇒ <b>网关对任何接口都回 404</b>。</p>
 * <p>因此密文列只用于回显，另存一列确定性 {@code interface_path_hash}
 * （HMAC-SHA256，固定密钥、无盐）专供等值查询：</p>
 * <pre>
 *   WHERE (interface_path_hash = H(candidate) OR interface_path = candidate)
 * </pre>
 * <p>两个分支共存 ⇒ 密文行命中 hash，历史明文行命中明文列，<b>存量数据不迁移也能继续工作</b>。
 * 用 HMAC 而非裸 SHA256：路径是低熵字符串（{@code /order/create}），
 * 裸摘要可被彩虹表秒破，加固定密钥后无 KEK 无法离线枚举。</p>
 *
 * <h3>密文格式</h3>
 * <p>{@code enc:v1:<base64(IV)>:<base64(ciphertext)>} —— AES-256-CBC + PKCS5Padding，
 * 每次加密使用<b>随机 16 字节 IV</b>（同一明文两次加密得到不同密文）。
 * {@code v1} 留给后续算法升级；无该前缀的值一律按历史明文处理并原样返回（演进式兼容）。</p>
 *
 * <h3>密钥来源</h3>
 * <p>复用落库 KEK {@code gatekeeper.crypto.aes-key}（与 app_secret、渠道密码同一把）。
 * 盲索引的 HMAC 密钥由此派生 + 消息前缀做域分离，<b>与加密用途隔离</b>。
 * ⚠ KEK 轮换必须把 interface_path / api_param 三列与 app_secret 同批重加密（同 T09 R8 约束）。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
public interface InterfaceCryptoService {

    /** 密文前缀（含版本号，便于后续平滑升级算法） */
    String ENC_PREFIX = "enc:v1:";

    /** 展示掩码：固定值，<b>绝不派生自明文</b>（否则会泄漏首尾字符） */
    String MASK = "****";

    /** 盲索引 HMAC 的域分离前缀 */
    String BLIND_INDEX_DOMAIN = "gk:bidx:interface_path:";

    /**
     * 接口信息加密是否启用（委托 {@code SysInterfaceCryptoConfigService}，带 10s 缓存）。
     * 读不到开关时返回 {@code true}（fail-safe 保持加密）。
     */
    boolean isEnabled();

    /**
     * 加密单个字段值。
     *
     * @param plain 明文；null/"" → 原样返回；已是密文 → 原样返回（幂等，不重复加密）
     * @return 密文（{@code enc:v1:...}）或原值
     */
    String encryptField(String plain);

    /**
     * 解密单个字段值。
     *
     * @param stored 库中值；null/""/无密文前缀（历史明文）→ 原样返回
     * @return 明文；解密失败时<b>原样返回入参</b>并 WARN（返回密文不泄漏信息，比抛异常安全）
     */
    String decryptField(String stored);

    /** 判断一个值是否为本服务产出的密文 */
    boolean isEncrypted(String value);

    /** 判断一个值是否是展示掩码（用于「掩码回写防线」） */
    boolean isMask(String value);

    /**
     * 计算盲索引：{@code HMAC-SHA256(aesKey, DOMAIN + plain)} 的小写十六进制。
     *
     * @param plain 明文；null/"" → null
     * @return 64 字符十六进制，或 null
     */
    String blindIndex(String plain);

    /**
     * <b>写路径</b>：就地把实体的 {@code interface_path} 加密、{@code interface_path_hash} 置为盲索引。
     *
     * <p>hash <b>无论开关是否启用都会计算</b>：它确定、幂等、可逆，且让网关的 hash 分支
     * 在开关切换后依然可用（避免"关过开关再打开"导致 hash 缺失）。</p>
     *
     * <p>已是密文（更新场景自库里带出的值）时直接返回，避免二次加密。</p>
     *
     * @param iface 待落库实体；为 null 或路径为空时不做任何事
     */
    void applyToInterface(ApiInterface iface);

    /** <b>读路径</b>：就地把实体的 {@code interface_path} 还原为明文（供内部逻辑使用） */
    void decryptInPlace(ApiInterface iface);

    /** <b>写路径</b>：加密 {@code field_name} / {@code example} / {@code description} */
    void applyToParam(ApiParam param);

    /** <b>读路径</b>：解密 {@code field_name} / {@code example} / {@code description} */
    void decryptInPlace(ApiParam param);

    /**
     * 生成接口路径的展示掩码。
     *
     * <p>用固定 {@code ****} 而不是「首4+****+末4」：路径是低熵字符串，
     * 泄漏首尾各 4 个字符（如 {@code /ord****ate}）已足以猜出接口，
     * 与「从控制台看不到」的目标相悖（裁定见 docs/CONTRACTS §18）。</p>
     */
    default String maskPath() {
        return MASK;
    }

    /** 使开关内存缓存立即失效 */
    void invalidate();
}
