// 引入 Vue CLI 提供的配置辅助函数
const { defineConfig } = require('@vue/cli-service')

// Vue CLI 项目构建与开发服务器配置
module.exports = defineConfig({
  // 转译 node_modules 中的依赖，避免部分第三方库语法不兼容
  transpileDependencies: true,
  // 关闭生产 Source Map：避免构建产物暴露全部前端源码（业务逻辑/接口路径），同时显著减小构建体积
  productionSourceMap: false,
  // 保存时关闭 ESLint 检查
  lintOnSave: false,
  devServer: {
    // 前端开发服务器端口
    port: 8081,
    proxy: {
      // 将以 /api 开头的请求代理到后端服务
      '/api': {
        // 后端服务地址
        target: 'http://localhost:8080',
        // 修改请求头中的 Host 为后端地址
        changeOrigin: true
      }
    }
  },
  configureWebpack: {
    // 设置打包产物名称
    name: 'GateKeeper'
  }
})
