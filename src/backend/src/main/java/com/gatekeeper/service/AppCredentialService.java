package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.dto.AppCredentialDto;
import com.gatekeeper.dto.CredentialRotateRequest;
import com.gatekeeper.entity.AppCredential;

import java.util.List;

/**
 * 应用凭证服务接口 — T03a 主数据三大基础域之一（最复杂）
 *
 * <p>「一应用多密钥 + 环境隔离 + 灰度轮换 + 零停机」的服务能力。
 *
 * <h3>关键流程</h3>
 * <ul>
 *   <li>{@link #rotate} 灰度轮换：原主密钥 → expireTime=now+7d（仍可调用），同时新建轮换中密钥</li>
 *   <li>{@link #completeRotate} 完成轮换：轮换中密钥变主密钥（rotateFlag=0），旧主密钥 status=3 已吊销</li>
 *   <li>{@link #revoke} 立即吊销（高危）：status=3 已吊销</li>
 * </ul></p>
 *
 * <h3>状态字典</h3>
 * <ul>
 *   <li>status 0=未分配, 1=启用中, 2=已停用, 3=已吊销, 4=已过期</li>
 *   <li>rotateFlag 0=主密钥, 1=轮换中</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
public interface AppCredentialService extends IService<AppCredential> {

    /**
     * 按应用 + 环境筛选凭证列表。
     *
     * @param appId   应用 ID（可空）
     * @param envCode 环境编码（可空）
     * @param status  凭证状态（可空）
     * @return 凭证列表（仅返回 DTO 字段，绝不返回明文 secret）
     */
    List<AppCredentialDto> list(Long appId, String envCode, Integer status);

    /**
     * 按主键查询凭证详情。
     *
     * @param id 凭证 ID
     * @return 凭证 DTO（不含明文 secret）
     */
    AppCredentialDto get(Long id);

    /**
     * 创建新凭证。
     *
     * <p>服务端生成 appKey（ak_{envCode}_{16位随机}）+ appSecret（64 位随机），
     * appSecret 落库前 AES-256 加密，返回时仅一次性返回明文。</p>
     *
     * @param dto 入参（appId / envCode / alias 必填）
     * @return 新凭证 DTO（含明文 secret —— 仅本次响应）
     * @throws IllegalArgumentException appId/envCode 非法 / env 不存在
     */
    AppCredentialDto create(AppCredentialDto dto);

    /**
     * 灰度轮换：原主密钥变为"将于 N 天后吊销"，同时新建轮换中密钥。
     *
     * <p>业务规则：
     * <ul>
     *   <li>若 appId+envCode 下已有 rotateFlag=1 的「轮换中」凭证 → 拒绝</li>
     *   <li>把现有「主密钥」（rotateFlag=0）的 alias 改名为「{原 alias}-旧(将于 N 天后吊销)」并写入 expireTime=now+N</li>
     *   <li>新建凭证 alias「{原 alias}-轮换中(新)」 rotateFlag=1 status=1</li>
     *   <li>仅本次响应返回新凭证的 appKey + appSecret 明文</li>
     * </ul></p>
     *
     * @param req 轮换请求
     * @return 新凭证 DTO（含明文 secret —— 仅本次响应）
     */
    AppCredentialDto rotate(CredentialRotateRequest req);

    /**
     * 完成轮换：轮换中凭证变主密钥，旧主密钥 status=3（已吊销）。
     *
     * @param appId   应用 ID
     * @param envCode 环境编码
     * @return 操作影响行数（成功应为 2：旧凭证吊销 + 新凭证降级）
     * @throws IllegalArgumentException 不存在轮换中凭证 / 不存在主密钥
     */
    int completeRotate(Long appId, String envCode);

    /**
     * 立即吊销凭证（高危，T02 @RequirePerm 已加 app:credential:revoke）。
     *
     * @param id 凭证 ID
     */
    void revoke(Long id);

    /**
     * 修改凭证别名 / 过期时间（不影响密钥本身）。
     *
     * @param id  凭证 ID
     * @param dto 入参（alias / expireTime 可改）
     */
    void update(Long id, AppCredentialDto dto);

    /**
     * 查看密钥明文（T14「二次查看」）。
     *
     * <p>语义：把库中 AES-256-ECB 密文形式的 {@code appSecret} <b>解密后返回</b>，
     * <b>不是</b>重新生成 —— 重新生成属 {@link #rotate} / {@link #revoke} 的能力范围。</p>
     *
     * <p>🔴 <b>调用方必须先做「当前账号密码」的二次确认</b>（{@code AccountPasswordVerifier}）。
     * 本方法只负责取密钥，不负责鉴权 —— 它假定调用方已经校验过。这是刻意的关注点分离：
     * 认证在 security 层，取数在业务层。</p>
     *
     * <p>已吊销（status=3）的凭证拒绝查看：历史密钥不应再被取出使用。</p>
     *
     * @param id 凭证 ID
     * @return 含明文 {@code appSecret} 的 DTO（对应 create / rotate 的「明文返回窗口」）
     * @throws com.gatekeeper.exception.GatewayException 凭证不存在 / 已吊销 / 密文缺失或解密失败
     */
    AppCredentialDto reveal(Long id);
}
