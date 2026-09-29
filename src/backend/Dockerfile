# ==================== 后端镜像（多阶段构建） ====================
# 阶段1：Maven + JDK8 编译打包（利用 Docker 层缓存，依赖未变时不重复下载）
# 阶段2：仅含 JRE 的精简运行时镜像

# ---- 阶段1：构建 ----
FROM maven:3.8.8-eclipse-temurin-8 AS build
WORKDIR /app
# 先只拷贝 pom.xml 下载依赖：源码改动不会触发依赖重新下载
COPY pom.xml .
RUN mvn -B dependency:go-offline
# 再拷贝源码打包（跳过测试：测试在 CI 阶段执行）
COPY src ./src
# 运行配置的来源（这一步不能省）：
#   src/main/resources/application.yml 是【本地真实配置】，含真实密钥与内网库地址，
#   已被 .dockerignore 排除在构建上下文之外。若不做任何处理，jar 里将没有任何
#   服务端配置 —— server.servlet.context-path=/api、数据源、MyBatis-Plus 等全部丢失，
#   容器必然启动失败（或行为异常）。
#   application.example.yml 是仓库自带的官方模板，与 application.yml 结构完全等价，
#   但三项密钥默认值为空、库地址默认为 localhost：正好由容器环境变量注入，
#   缺失时 SecurityStartupCheck 直接拒绝启动（fail-fast），不会以弱默认值裸奔。
RUN cp src/main/resources/application.example.yml src/main/resources/application.yml \
 && mvn -B package -DskipTests

# ---- 阶段2：运行 ----
FROM eclipse-temurin:8-jre
# 版本号由构建脚本注入（--build-arg VERSION=x.y.z），写进 OCI 标签，
# 使 `docker inspect` 能直接查到镜像版本，而不只依赖 tag
ARG VERSION=1.0.2
LABEL org.opencontainers.image.title="GateKeeper Backend" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/Change-Only/GateKeeper" \
      org.opencontainers.image.description="GateKeeper APIM 统一接口管理平台 - 后端网关服务（Spring Boot 2.7 / JDK8）"
WORKDIR /app
COPY --from=build /app/target/gatekeeper.jar app.jar
# 按容器内存配额动态设定堆上限，避免固定 -Xmx 与容器资源限制冲突
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
EXPOSE 8080
