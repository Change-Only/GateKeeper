package com.gatekeeper.gateway.router;

import com.gatekeeper.entity.ApiVersion;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GrayscaleRouter 纯逻辑单元测试
 *
 * <p>覆盖：grayRatio=0 → current 版本；grayRatio=100 → 灰度版本（全量）；
 * 空列表 / null → 返回 null；相同 stableHash 结果稳定；桶号边界判定。</p>
 */
class GrayscaleRouterTest {

    private ApiVersion current(int grayRatio) {
        ApiVersion v = new ApiVersion();
        v.setVersion("v1");
        v.setIsCurrent(1);
        v.setGrayRatio(grayRatio);
        return v;
    }

    private ApiVersion gray(int grayRatio) {
        ApiVersion v = new ApiVersion();
        v.setVersion("v2");
        v.setIsCurrent(0);
        v.setGrayRatio(grayRatio);
        return v;
    }

    @Test
    void chooseVersion_whenGrayRatioZero_returnsCurrent() {
        List<ApiVersion> versions = Arrays.asList(current(0), gray(0));
        assertEquals("v1", GrayscaleRouter.chooseVersion(versions, 12345L));
    }

    @Test
    void chooseVersion_whenGrayRatioHundred_returnsGrayForAnyHash() {
        List<ApiVersion> versions = Arrays.asList(current(0), gray(100));
        // 任意 stableHash，bucket ∈ [0,99] 恒 < 100 → 必走灰度
        for (long h = 0; h < 200; h++) {
            assertEquals("v2", GrayscaleRouter.chooseVersion(versions, h), "grayRatio=100 应全量走灰度");
        }
    }

    @Test
    void chooseVersion_whenEmptyOrNull_returnsNull() {
        assertNull(GrayscaleRouter.chooseVersion(Collections.emptyList(), 1L));
        assertNull(GrayscaleRouter.chooseVersion(null, 1L));
    }

    @Test
    void chooseVersion_isDeterministicForSameHash() {
        List<ApiVersion> versions = Arrays.asList(current(0), gray(50));
        long hash = 987654321L;
        String first = GrayscaleRouter.chooseVersion(versions, hash);
        String second = GrayscaleRouter.chooseVersion(versions, hash);
        assertEquals(first, second, "相同 stableHash 必须得到稳定结果");
    }

    @Test
    void chooseVersion_bucketBoundary_selectsVersionByRatio() {
        // grayRatio=50：bucket 必须 < 50 才走灰度
        List<ApiVersion> versions = Arrays.asList(current(0), gray(50));
        assertEquals("v2", GrayscaleRouter.chooseVersion(versions, 10L), "bucket=10 < 50 → 灰度版本");
        assertEquals("v1", GrayscaleRouter.chooseVersion(versions, 60L), "bucket=60 >= 50 → 当前版本");
    }

    @Test
    void chooseVersion_whenNoGrayCandidate_returnsCurrent() {
        // 只有 current，无 grayRatio>0 的候选 → 始终 current，与 hash 无关
        List<ApiVersion> versions = Collections.singletonList(current(0));
        assertEquals("v1", GrayscaleRouter.chooseVersion(versions, 42L));
        assertEquals("v1", GrayscaleRouter.chooseVersion(versions, 9999L));
    }
}
