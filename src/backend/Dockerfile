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
RUN mvn -B package -DskipTests

# ---- 阶段2：运行 ----
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /app/target/gatekeeper.jar app.jar
# 按容器内存配额动态设定堆上限，避免固定 -Xmx 与容器资源限制冲突
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
EXPOSE 8080
