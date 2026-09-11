package com.gatekeeper.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RequirePerm 注解单测
 *
 * <p>验证注解的元注解、属性与默认值。</p>
 */
@DisplayName("@RequirePerm 注解")
class RequirePermAnnotationTest {

    /** 直接挂在测试方法上的注解 */
    @RequirePerm(value = "app:credential:reset", risk = true)
    void annotatedMethod() {
        // 用于反射读取
    }

    /** 默认 risk=false 的注解 */
    @RequirePerm("app:list")
    void annotatedDefaultMethod() {
        // 用于反射读取
    }

    @Test
    @DisplayName("注解类存在且可加载")
    void annotationTypeExists() {
        assertNotNull(RequirePerm.class);
        // 必须带 @Retention(RUNTIME)
        Retention retention = RequirePerm.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());

        // 必须带 @Target({METHOD, TYPE})
        Target target = RequirePerm.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] types = target.value();
        assertTrue(contains(types, ElementType.METHOD), "支持 METHOD");
        assertTrue(contains(types, ElementType.TYPE), "支持 TYPE");
    }

    @Test
    @DisplayName("value() 与 risk() 都能正确读取")
    void readsValueAndRisk() throws NoSuchMethodException {
        java.lang.reflect.Method m1 = RequirePermAnnotationTest.class.getDeclaredMethod("annotatedMethod");
        RequirePerm ann1 = m1.getAnnotation(RequirePerm.class);
        assertNotNull(ann1);
        assertEquals("app:credential:reset", ann1.value());
        assertTrue(ann1.risk(), "risk=true 显式标注");

        java.lang.reflect.Method m2 = RequirePermAnnotationTest.class.getDeclaredMethod("annotatedDefaultMethod");
        RequirePerm ann2 = m2.getAnnotation(RequirePerm.class);
        assertNotNull(ann2);
        assertEquals("app:list", ann2.value());
        assertFalse(ann2.risk(), "risk 默认 false");
    }

    private static boolean contains(ElementType[] arr, ElementType t) {
        for (ElementType et : arr) {
            if (et == t) return true;
        }
        return false;
    }
}
