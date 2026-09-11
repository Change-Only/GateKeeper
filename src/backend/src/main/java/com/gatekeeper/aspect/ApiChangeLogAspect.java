package com.gatekeeper.aspect;

import cn.hutool.json.JSONUtil;
import com.gatekeeper.service.ApiChangeLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 接口变更留痕切面 — T03b 变更历史自动写入
 *
 * <p>切点：标注了 {@link ApiChangeLog} 的方法（本包注解，与实体
 * {@code com.gatekeeper.entity.ApiChangeLog} 同名但语义不同）。</p>
 *
 * <p>行为：
 * <ol>
 *   <li>仅在方法<strong>正常返回</strong>后触发（{@code @AfterReturning}）——异常不落痕</li>
 *   <li>解析 apiId（入参/返回对象上的 getApiId() → 名为 apiId 的入参 → 返回 ApiInterface 主键）</li>
 *   <li>解析操作人（请求属性 X-USER-ID / X-USERNAME）</li>
 *   <li>newValue = 返回对象 JSON（截断 1000 字符）</li>
 *   <li>事务提交后写入（afterCommit）；无事务上下文则立即写入</li>
 * </ol></p>
 *
 * <p><strong>容错</strong>：留痕失败仅告警，绝不阻断业务返回（审计不应影响主链路）。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ApiChangeLogAspect {

    /** new_value 落库长度上限（列宽 1024，留 24 字符余量） */
    private static final int NEW_VALUE_MAX_LEN = 1000;

    private final ApiChangeLogService apiChangeLogService;

    /** 参数名发现器（Spring Boot 默认 -parameters 编译，可直接取到形参名） */
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 环绕后的成功通知：写入一条接口变更历史。
     *
     * <p><strong>注意 1</strong>：本通知<strong>不</strong>通过 {@code @annotation(xxx)}
     * 绑定注解入参，而是从方法签名反射读取注解。原因是 Spring AOP 对
     * {@code @annotation(全限定名)} 形式只做「匹配」不做「绑定」，若在通知方法上再声明
     * 一个注解类型的形参，AspectJ 会在启动期抛
     * {@code IllegalArgumentException: error at ::0 formal unbound in pointcut}——
     * 这正是早期版本的启动失败根因。反射读取可完全规避该问题。</p>
     *
     * <p><strong>注意 2</strong>：Controller 方法返回的是 {@code Result<T>} 包装体，
     * 真正的业务对象在 {@code data} 里。因此解析 apiId / 生成 newValue 前必须
     * {@link #unwrap(Object)} 拆包，否则形如「PUT /gray」「POST /test」这类
     * 仅靠返回体才能定位 apiId 的接口会漏记留痕。</p>
     *
     * @param joinPoint 连接点
     * @param result    方法返回值（可空，通常是 {@code Result<T>} 包装体）
     */
    @AfterReturning(pointcut = "@annotation(com.gatekeeper.aspect.ApiChangeLog)", returning = "result")
    public void afterReturning(JoinPoint joinPoint, Object result) {
        com.gatekeeper.aspect.ApiChangeLog annotation = resolveAnnotation(joinPoint);
        if (annotation == null) {
            // 理论不可达（切点即按注解放行），兜底避免 NPE
            return;
        }
        try {
            Object payload = unwrap(result);
            Long apiId = resolveApiId(joinPoint, payload);
            if (apiId == null) {
                log.warn("ApiChangeLog skipped: cannot resolve apiId, method={}",
                        joinPoint.getSignature().toShortString());
                return;
            }

            Long operatorId = null;
            String operatorName = null;
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                Object uid = request.getAttribute("X-USER-ID");
                if (uid instanceof Number) {
                    operatorId = ((Number) uid).longValue();
                }
                Object uname = request.getAttribute("X-USERNAME");
                if (uname != null) {
                    operatorName = String.valueOf(uname);
                }
            }

            com.gatekeeper.entity.ApiChangeLog entity = new com.gatekeeper.entity.ApiChangeLog();
            entity.setApiId(apiId);
            entity.setChangeType(blankToDefault(annotation.changeType(), "UPDATE"));
            entity.setFieldName(emptyToNull(annotation.fieldName()));
            entity.setFieldLabel(emptyToNull(annotation.fieldLabel()));
            entity.setChangeReason(emptyToNull(annotation.value()));
            entity.setNewValue(toJsonTruncated(payload));
            entity.setOperatorId(operatorId);
            entity.setOperatorName(operatorName);
            entity.setCreateTime(LocalDateTime.now());

            registerAfterCommit(() -> apiChangeLogService.save(entity));
        } catch (Exception ex) {
            // 留痕失败不得影响业务返回
            log.warn("ApiChangeLog record failed: method={}, cause={}",
                    joinPoint.getSignature().toShortString(), ex.getMessage());
        }
    }

    /**
     * 从连接点方法签名反射读取 {@link ApiChangeLog} 注解。
     *
     * <p>方法级注解优先，其次回退到声明类的类级注解。</p>
     *
     * @param joinPoint 连接点
     * @return 注解实例；读取不到返回 null
     */
    private com.gatekeeper.aspect.ApiChangeLog resolveAnnotation(JoinPoint joinPoint) {
        try {
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            Method method = sig.getMethod();
            com.gatekeeper.aspect.ApiChangeLog ann =
                    method.getAnnotation(com.gatekeeper.aspect.ApiChangeLog.class);
            if (ann != null) {
                return ann;
            }
            return method.getDeclaringClass().getAnnotation(com.gatekeeper.aspect.ApiChangeLog.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 拆包 {@code Result<T>}：Controller 统一返回 {@code Result} 包装体，
     * 业务对象位于其 {@code data} 字段。非 {@code Result} 时原样返回。
     *
     * @param result 方法返回值
     * @return 业务对象（data）或原值
     */
    private Object unwrap(Object result) {
        if (result instanceof com.gatekeeper.common.Result) {
            return ((com.gatekeeper.common.Result<?>) result).getData();
        }
        return result;
    }

    /**
     * 解析接口ID。
     *
     * @param joinPoint 连接点
     * @param payload   已拆包的方法返回值（可能是 DTO / ApiInterface / null）
     * @return apiId；无法解析时返回 null
     */
    private Long resolveApiId(JoinPoint joinPoint, Object payload) {
        Object[] args = joinPoint.getArgs();

        // 1) 任一入参对象带 getApiId()
        if (args != null) {
            for (Object a : args) {
                Long v = readApiId(a);
                if (v != null) {
                    return v;
                }
            }
        }

        // 2) 名为 apiId 的入参（Number）
        if (args != null) {
            String[] names = parameterNames(joinPoint);
            if (names != null) {
                for (int i = 0; i < args.length && i < names.length; i++) {
                    if ("apiId".equals(names[i]) && args[i] instanceof Number) {
                        return ((Number) args[i]).longValue();
                    }
                }
            }
        }

        // 3) 返回对象（已拆包）带 getApiId()
        Long fromResult = readApiId(payload);
        if (fromResult != null) {
            return fromResult;
        }

        // 4) 返回的 ApiInterface 主键
        if (payload instanceof com.gatekeeper.entity.ApiInterface) {
            return ((com.gatekeeper.entity.ApiInterface) payload).getId();
        }
        return null;
    }

    /**
     * 反射读取对象上的 getApiId()。
     */
    private Long readApiId(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            Method m = obj.getClass().getMethod("getApiId");
            Object val = m.invoke(obj);
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
        } catch (Exception ignore) {
            // 无该方法 → 忽略
        }
        return null;
    }

    /**
     * 获取方法形参名（依赖 -parameters 编译；失败返回 null）。
     */
    private String[] parameterNames(JoinPoint joinPoint) {
        try {
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            return parameterNameDiscoverer.getParameterNames(sig.getMethod());
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 将返回值序列化为 JSON 并截断。
     */
    private String toJsonTruncated(Object result) {
        if (result == null) {
            return null;
        }
        try {
            String json = JSONUtil.toJsonStr(result);
            if (json == null) {
                return null;
            }
            return json.length() > NEW_VALUE_MAX_LEN ? json.substring(0, NEW_VALUE_MAX_LEN) : json;
        } catch (Exception ex) {
            String s = String.valueOf(result);
            return s.length() > NEW_VALUE_MAX_LEN ? s.substring(0, NEW_VALUE_MAX_LEN) : s;
        }
    }

    /**
     * 事务 afterCommit 后执行；无事务上下文则立即执行。
     */
    private void registerAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            task.run();
                        } catch (Exception ex) {
                            log.warn("ApiChangeLog afterCommit write failed: {}", ex.getMessage());
                        }
                    }
                });
                return;
            } catch (Exception ex) {
                log.warn("Register afterCommit for ApiChangeLog failed, write inline: {}", ex.getMessage());
            }
        }
        task.run();
    }

    /**
     * 空串 → null。
     */
    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /**
     * 空串 → 默认值。
     */
    private String blankToDefault(String s, String def) {
        return (s == null || s.trim().isEmpty()) ? def : s.trim();
    }
}
