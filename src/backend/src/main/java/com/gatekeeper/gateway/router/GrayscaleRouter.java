package com.gatekeeper.gateway.router;

import com.gatekeeper.entity.ApiVersion;

import java.util.List;
import java.util.Objects;

/**
 * 灰度版本路由纯逻辑 — 数据面版本分流核心算法
 *
 * <p>根据接口版本列表与稳定哈希值，决定单个请求应路由到哪个版本：</p>
 * <ul>
 *   <li>current 版本（is_current=1）承接默认/绝大多数流量；</li>
 *   <li>候选（灰度）版本（grayRatio&gt;0 且非 current）按 grayRatio 百分比承接流量；</li>
 *   <li>通过 {@code Math.floorMod(stableHash, 100)} 得到 [0,99] 的桶号，
 *       桶号 &lt; grayRatio 时走灰度版本，否则走 current。</li>
 * </ul>
 * <p>stableHash 由调用方以 appId 稳定哈希提供，保证同一应用始终落在同一桶，
 * 避免单次会话在版本间抖动。本类为无状态工具类，不依赖 Spring / 外部存储，便于单元测试。</p>
 *
 * @author GateKeeper
 * @since T04-B (APIM V2 网关灰度路由)
 */
public final class GrayscaleRouter {

    private GrayscaleRouter() {
        // 工具类，禁止实例化
    }

    /**
     * 选择目标版本号
     *
     * @param versions   接口版本列表（来自 api_version 表，已按 apiId 过滤；可为 null/空）
     * @param stableHash 稳定哈希值（建议由 appId 派生，范围任意 long）
     * @return 目标版本号字符串（如 "v1"）；无可用版本时返回 {@code null}
     */
    public static String chooseVersion(List<ApiVersion> versions, long stableHash) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }

        ApiVersion current = null;
        ApiVersion gray = null;
        for (ApiVersion v : versions) {
            if (v == null) {
                continue;
            }
            // 标记 current：is_current == 1 的第一个版本
            if (current == null && isCurrentVersion(v)) {
                current = v;
            }
            // 标记灰度候选：非 current 且 grayRatio > 0 的第一个版本
            Integer grayRatio = v.getGrayRatio();
            if (gray == null && !isCurrentVersion(v) && grayRatio != null && grayRatio > 0) {
                gray = v;
            }
        }

        // 防御：没有任何版本被标记为 current（数据异常），退化为第一个可用版本
        if (current == null) {
            current = versions.stream().filter(Objects::nonNull).findFirst().orElse(null);
        }
        if (current == null) {
            return null;
        }

        // 无灰度候选，或灰度比例为 0/空 → 直接走 current
        if (gray == null) {
            return current.getVersion();
        }
        int grayRatio = gray.getGrayRatio() == null ? 0 : gray.getGrayRatio();
        if (grayRatio <= 0) {
            return current.getVersion();
        }

        // 稳定哈希落桶：在 [0,99] 内取桶号，桶号 < grayRatio 走灰度版本
        // Math.floorMod(long, int) 返回 long，结果范围 [-99,99]，安全强转 int
        int bucket = (int) Math.floorMod(stableHash, 100);
        return bucket < grayRatio ? gray.getVersion() : current.getVersion();
    }

    /**
     * 判断版本是否为当前默认版本（is_current=1），对空值做防御。
     */
    private static boolean isCurrentVersion(ApiVersion v) {
        return v.getIsCurrent() != null && v.getIsCurrent() == 1;
    }
}
