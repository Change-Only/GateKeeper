package com.gatekeeper.service;

import com.gatekeeper.vo.AppInterfaceDocVo;

/**
 * 应用「接口文档」数据服务 — T16-2
 *
 * <p>只导出该应用<b>有权限</b>的接口（授权已生效 + 在有效期内 + 接口已启用），
 * 并附带 {@code api_param} 参数明细，供前端渲染成 Markdown 对外交付。</p>
 *
 * @author GateKeeper
 * @since T16-2 (2026-09-15)
 */
public interface AppInterfaceDocService {

    /**
     * 构建指定应用的接口文档数据。
     *
     * @param appId 应用 ID
     * @return 文档数据（应用不存在时抛 {@code GatewayException.badRequest}）
     */
    AppInterfaceDocVo build(Long appId);
}
