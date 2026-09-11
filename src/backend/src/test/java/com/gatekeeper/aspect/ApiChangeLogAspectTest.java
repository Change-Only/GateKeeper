package com.gatekeeper.aspect;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.service.ApiChangeLogService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiChangeLogAspect 单测 —— 验证 @ApiChangeLog 触发写入、changeType 与 apiId/操作人解析。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiChangeLogAspect 自动留痕")
class ApiChangeLogAspectTest {

    @Mock
    private ApiChangeLogService apiChangeLogService;

    private ApiChangeLogAspect aspect;

    /** 承载注解的测试目标（模拟 Controller 方法签名）。 */
    static class Target {

        @ApiChangeLog(value = "新增接口", changeType = "CREATE", fieldName = "interface", fieldLabel = "接口")
        public ApiInterface create(ApiInterface i) {
            return i;
        }

        @ApiChangeLog(value = "发布版本", changeType = "PUBLISH", fieldName = "publishStatus")
        public ApiVersionDto publish(Long id) {
            return null;
        }

        @ApiChangeLog(changeType = "UPDATE", fieldName = "params")
        public Object batchSave(ApiParamDto dto) {
            return dto;
        }

        @ApiChangeLog(value = "设置灰度", changeType = "UPDATE", fieldName = "grayRatio")
        public Result<ApiVersionDto> gray(Long id) {
            return null;
        }

        @ApiChangeLog
        public void unresolvable(String foo) {
        }
    }

    @BeforeEach
    void setUp() {
        aspect = new ApiChangeLogAspect(apiChangeLogService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void invoke(String method, Class<?>[] types, Object[] args, Object result) throws Exception {
        Method m = Target.class.getMethod(method, types);
        JoinPoint jp = mock(JoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(jp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(m);
        when(jp.getArgs()).thenReturn(args);
        // 切面已成 2 参（JoinPoint, result）：注解由连接点方法签名反射解析
        aspect.afterReturning(jp, result);
    }

    @Test
    @DisplayName("create：从返回的 ApiInterface 解析 apiId，changeType=CREATE")
    void create_resolvesApiIdFromResult() throws Exception {
        ApiInterface result = new ApiInterface();
        result.setId(100L);
        result.setInterfaceName("下单");
        invoke("create", new Class<?>[]{ApiInterface.class}, new Object[]{result}, result);

        ArgumentCaptor<com.gatekeeper.entity.ApiChangeLog> captor =
                ArgumentCaptor.forClass(com.gatekeeper.entity.ApiChangeLog.class);
        verify(apiChangeLogService, times(1)).save(captor.capture());
        assertEquals(100L, captor.getValue().getApiId());
        assertEquals("CREATE", captor.getValue().getChangeType());
        assertEquals("interface", captor.getValue().getFieldName());
        assertNotNull(captor.getValue().getCreateTime());
        assertNotNull(captor.getValue().getNewValue());
    }

    @Test
    @DisplayName("publish：从返回的 ApiVersionDto 解析 apiId，changeType=PUBLISH")
    void publish_resolvesApiIdFromResultDto() throws Exception {
        ApiVersionDto result = new ApiVersionDto();
        result.setId(10L);
        result.setApiId(55L);
        result.setVersion("v2");
        invoke("publish", new Class<?>[]{Long.class}, new Object[]{10L}, result);

        ArgumentCaptor<com.gatekeeper.entity.ApiChangeLog> captor =
                ArgumentCaptor.forClass(com.gatekeeper.entity.ApiChangeLog.class);
        verify(apiChangeLogService, times(1)).save(captor.capture());
        assertEquals(55L, captor.getValue().getApiId());
        assertEquals("PUBLISH", captor.getValue().getChangeType());
    }

    @Test
    @DisplayName("batchSave：从入参 DTO 的 getApiId() 解析 apiId")
    void batchSave_resolvesApiIdFromArgDto() throws Exception {
        ApiParamDto dto = new ApiParamDto();
        dto.setApiId(77L);
        invoke("batchSave", new Class<?>[]{ApiParamDto.class}, new Object[]{dto}, dto);

        ArgumentCaptor<com.gatekeeper.entity.ApiChangeLog> captor =
                ArgumentCaptor.forClass(com.gatekeeper.entity.ApiChangeLog.class);
        verify(apiChangeLogService, times(1)).save(captor.capture());
        assertEquals(77L, captor.getValue().getApiId());
        assertEquals("UPDATE", captor.getValue().getChangeType());
    }

    @Test
    @DisplayName("灰色/连通性测试：返回 Result<T> 包装体时自动拆包解析 apiId")
    void unwrapsResultWrapper() throws Exception {
        ApiVersionDto dto = new ApiVersionDto();
        dto.setId(4L);
        dto.setApiId(1L);
        dto.setVersion("v2");
        Result<ApiVersionDto> wrapped = Result.success(dto);
        invoke("gray", new Class<?>[]{Long.class}, new Object[]{4L}, wrapped);

        ArgumentCaptor<com.gatekeeper.entity.ApiChangeLog> captor =
                ArgumentCaptor.forClass(com.gatekeeper.entity.ApiChangeLog.class);
        verify(apiChangeLogService, times(1)).save(captor.capture());
        assertEquals(1L, captor.getValue().getApiId());
        assertEquals("grayRatio", captor.getValue().getFieldName());
        // newValue 应为拆包后的业务对象 JSON（含 version），而非 Result 包装体
        assertNotNull(captor.getValue().getNewValue());
        org.junit.jupiter.api.Assertions.assertTrue(
                captor.getValue().getNewValue().contains("v2"),
                "newValue 应为拆包后的 ApiVersionDto JSON");
    }

    @Test
    @DisplayName("无法解析 apiId → 不写留痕（容错）")
    void unresolvable_skips() throws Exception {
        invoke("unresolvable", new Class<?>[]{String.class}, new Object[]{"x"}, null);
        verify(apiChangeLogService, never()).save(any(com.gatekeeper.entity.ApiChangeLog.class));
    }

    @Test
    @DisplayName("操作人从请求属性 X-USER-ID / X-USERNAME 解析")
    void resolvesOperatorFromRequest() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("X-USER-ID", 7L);
        req.setAttribute("X-USERNAME", "alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        ApiParamDto dto = new ApiParamDto();
        dto.setApiId(9L);
        invoke("batchSave", new Class<?>[]{ApiParamDto.class}, new Object[]{dto}, dto);

        ArgumentCaptor<com.gatekeeper.entity.ApiChangeLog> captor =
                ArgumentCaptor.forClass(com.gatekeeper.entity.ApiChangeLog.class);
        verify(apiChangeLogService, times(1)).save(captor.capture());
        assertEquals(7L, captor.getValue().getOperatorId());
        assertEquals("alice", captor.getValue().getOperatorName());
    }
}
