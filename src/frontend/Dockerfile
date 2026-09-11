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
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
