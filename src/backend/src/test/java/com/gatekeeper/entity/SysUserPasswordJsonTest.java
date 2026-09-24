package com.gatekeeper.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-SEC-1 P1-7 回归：{@code SysUser.password} 必须「只进不出」。
 *
 * <p>修复前 {@code GET /system/user/list} 会把 BCrypt 散列序列化进响应体
 * （配合 {@code spring.jackson.default-property-inclusion: always}）。
 * 现改为 {@code @JsonProperty(access = WRITE_ONLY)}：</p>
 * <ul>
 *   <li><b>序列化</b>（出参）→ 不含 password</li>
 *   <li><b>反序列化</b>（入参）→ password 仍可被读入（登录 / 建号 / 改密链路依赖）</li>
 * </ul>
 *
 * <p>可证伪：把注解换成 {@code @JsonIgnore}（或删掉）——序列化断言仍过，
 * 但反序列化断言会失败（{@code @JsonIgnore} 连写入一起禁）。把注解删掉则序列化断言失败。</p>
 */
@DisplayName("SysUser.password 序列化保护（P1-7）")
class SysUserPasswordJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("序列化出参不含 password（不泄漏 BCrypt 散列）")
    void serialize_doesNotExposePassword() throws Exception {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("$2b$10$abcdefghijklmnopqrstuv");
        user.setStatus(1);

        String json = mapper.writeValueAsString(user);
        assertFalse(json.contains("password"), "响应体不应包含 password 字段：" + json);
        assertFalse(json.contains("$2b$10$"), "响应体不应包含 BCrypt 散列");
        assertTrue(json.contains("\"username\":\"admin\""), "其余字段应正常输出");
    }

    @Test
    @DisplayName("反序列化入参仍能读入 password（登录/建号/改密链路不受影响）")
    void deserialize_stillReadsPassword() throws Exception {
        String body = "{\"username\":\"alice\",\"password\":\"PlainPassw0rd!\"}";

        SysUser user = mapper.readValue(body, SysUser.class);
        assertEquals("alice", user.getUsername());
        assertEquals("PlainPassw0rd!", user.getPassword(), "password 必须仍可从请求体写入");
    }
}
