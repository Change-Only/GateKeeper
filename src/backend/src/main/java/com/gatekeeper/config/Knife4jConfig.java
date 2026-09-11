package com.gatekeeper.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 接口文档配置
 * 为项目生成在线 API 文档，便于前后端联调与接口查阅
 */
@Configuration
public class Knife4jConfig {

    /**
     * 构建 OpenAPI 文档基础信息（标题、描述、版本）
     *
     * @return OpenAPI 文档对象
     */
    @Bean
    public OpenAPI gatekeeperOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("GateKeeper API")
                .description("GateKeeper API 集中权限管理与安全网关系统")
                .version("1.0.0"));
    }
}
