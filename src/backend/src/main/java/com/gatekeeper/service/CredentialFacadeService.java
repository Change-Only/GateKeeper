package com.gatekeeper.service;

import com.gatekeeper.entity.AppCredential;

/**
 * 凭证门面服务 — 网关数据面调用入口（T03a 仅创建，不被调用）
 *
 * <p>T03a 阶段只创建该服务接口 + 默认实现骨架，<strong>本阶段（T03a）不调用</strong>，
 * 等待 T04 网关数据面改造时启用。该门面服务于隔离「多套凭证」业务知识，避免 AppAuthHandler
 * 直接持有 mapper。</p>
 *
 * <h3>T03a 提供的能力</h3>
 * <ul>
 *   <li>{@link #getActiveCredential} —— 按 appId+envCode 返回当前活跃凭证（优先主密钥，
 *       否则轮换中密钥，否则吊销/过期/停用返回 null）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
public interface CredentialFacadeService {

    /**
     * 查询应用在指定环境下的「当前活跃凭证」。
     *
     * <p>选择策略：
     * <ol>
     *   <li>主密钥（rotateFlag=0, status=1）</li>
     *   <li>轮换中密钥（rotateFlag=1, status=1）—— 在轮换过渡期使用，否则会拖长吊销窗口</li>
     *   <li>其他状态（status=0/2/3/4）跳过</li>
     * </ol></p>
     *
     * @param appId   应用 ID
     * @param envCode 环境编码
     * @return 活跃凭证；无活跃凭证时返回 null
     */
    AppCredential getActiveCredential(Long appId, String envCode);
}
