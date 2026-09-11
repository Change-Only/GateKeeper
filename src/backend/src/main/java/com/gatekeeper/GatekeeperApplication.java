package com.gatekeeper;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GateKeeper 系统启动类
 * API 集中权限管理与安全网关系统入口
 */
@SpringBootApplication
@MapperScan("com.gatekeeper.mapper") // 扫描 MyBatis-Plus Mapper 接口
@EnableAsync    // 启用异步能力，用于调用日志异步落库
@EnableScheduling // 启用定时任务，用于日志归档清理等
public class GatekeeperApplication {

    /**
     * 应用启动入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GatekeeperApplication.class, args);
    }
}
