# ==================== 前端镜像（多阶段构建） ====================
# 阶段1：Node 构建 SPA 产物
# 阶段2：Nginx 托管静态资源 + /api 反向代理到后端容器

# ---- 阶段1：构建 ----
FROM node:16-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install --registry=https://registry.npmmirror.com
COPY . .
RUN npm run build

# ---- 阶段2：运行 ----
FROM nginx:1.25-alpine
# 版本号由构建脚本注入（--build-arg VERSION=x.y.z），写进 OCI 标签，
# 使 `docker inspect` 能直接查到镜像版本，而不只依赖 tag
ARG VERSION=1.0.1
LABEL org.opencontainers.image.title="GateKeeper Frontend" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/Change-Only/GateKeeper" \
      org.opencontainers.image.description="GateKeeper APIM 统一接口管理平台 - 前端管理后台（Vue2 + Element UI + Nginx）"
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
