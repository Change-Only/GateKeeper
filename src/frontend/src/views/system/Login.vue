<template>
  <!-- 登录页：左右分割布局（左品牌区 + 右登录区） -->
  <div class="login-wrap">
    <!-- 左·品牌区：深海军蓝渐变 + 盾牌 Logo + 价值主张 + 安全特性 -->
    <div class="login-left">
      <div class="brand-inner">
        <div class="brand-logo">
          <svg viewBox="0 0 48 48" fill="none">
            <path d="M24 4 L42 10 V22 C42 34 34 41 24 44 C14 41 6 34 6 22 V10 Z" fill="rgba(79,140,255,.15)" stroke="#4f8cff" stroke-width="2.5"/>
            <path d="M24 12 L34 16 V24 C34 31 29 36 24 38 C19 36 14 31 14 24 V16 Z" fill="rgba(53,200,240,.18)" stroke="#35c8f0" stroke-width="1.8"/>
            <path d="M17 24 l5 5 9 -10" stroke="#6ea8ff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <div>
            <div class="t1">GateKeeper</div>
            <div class="t2">API 集中权限管理与安全网关</div>
          </div>
        </div>
        <div class="brand-slogan">守住每一次调用，<br>让 <em>可管 · 可控 · 可防 · 可审</em> 成为日常</div>
        <div class="brand-desc">统一接口入口，集中权限管控。国密加解密、流量限流、异常检测与 IP 封禁，为企业的每一次 API 调用保驾护航。</div>
        <div class="brand-feats">
          <div class="feat"><div class="feat-ico">🛡</div><div><b>细粒度权限</b><span>应用 × 接口 × 分组三级授权矩阵</span></div></div>
          <div class="feat"><div class="feat-ico">🔐</div><div><b>国密加密</b><span>SM2 / SM3 / SM4 + AES 全链路加密传输</span></div></div>
          <div class="feat"><div class="feat-ico">⚡</div><div><b>异常可防</b><span>5 类异常检测 + 自动 IP 封禁 + 多渠道告警</span></div></div>
        </div>
      </div>
    </div>
    <!-- 右·登录区：白底居中登录卡片 -->
    <div class="login-right">
      <div class="login-card">
        <h2>欢迎回来</h2>
        <div class="sub">登录 GateKeeper 管理平台，开始守护你的 API</div>
        <!-- 登录表单，回车提交时触发 login -->
        <el-form :model="form" @submit.native.prevent="login">
          <div class="field"><label>账号</label>
            <el-input v-model="form.username" placeholder="请输入管理员账号" prefix-icon="el-icon-user" />
          </div>
          <div class="field"><label>密码</label>
            <el-input v-model="form.password" placeholder="请输入登录密码" type="password" prefix-icon="el-icon-lock" show-password />
          </div>
          <div class="login-opt">
            <el-checkbox v-model="remember">记住我</el-checkbox>
            <span class="link" @click="$message.info('请联系系统管理员重置密码')">忘记密码？</span>
          </div>
          <el-button type="primary" class="login-btn" :loading="loading" @click="login">登 录</el-button>
        </el-form>
        <div class="login-foot">还没有 AppKey？<span class="link" @click="$router.push('/access-doc')">接入文档 →</span> · <span class="link" @click="$message.info('请联系系统管理员开通应用账号')">联系管理员</span></div>
      </div>
    </div>
  </div>
</template>
<script>
import { login } from '@/api/modules'
export default {
  data() {
    return {
      // 登录表单数据：刻意留空，不在前端预填任何账号密码。
      // 默认账号/密码请见 README「默认账号与密钥」，部署后必须修改。
      form: { username: '', password: '' },
      // 记住我选项
      remember: true,
      // 登录请求进行中标记（防重复提交）
      loading: false
    }
  },
  methods: {
    // 登录：调用后端认证接口校验账号密码，成功后保存令牌与用户信息并跳转仪表盘
    async login() {
      if (!this.form.username || !this.form.password) {
        this.$message.warning('请输入账号和密码')
        return
      }
      this.loading = true
      try {
        const res = await login({ username: this.form.username, password: this.form.password })
        // 统一写入 token / 用户 / 权限点，并依据 perms 计算可访问菜单
        // 登录返回结构：{ token, user, perms[], permCount }
        this.$store.dispatch('applyAuth', {
          token: res.data.token,
          user: res.data.user,
          perms: res.data.perms || []
        })
        this.$message.success('登录成功')
        this.$router.push('/dashboard')
      } catch (e) {
        // 错误信息由 axios 拦截器统一提示（含账号锁定提示）
      } finally {
        this.loading = false
      }
    }
  }
}
</script>
<style scoped>
.login-wrap { display: flex; min-height: 100vh; }

/* 左·品牌区 */
.login-left {
  flex: 0 0 52%;
  background: linear-gradient(160deg, #0b1c33 0%, #0f2a52 55%, #12336a 100%);
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  color: #fff;
}
/* 细网格纹理 */
.login-left::before {
  content: "";
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(120, 170, 255, .05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(120, 170, 255, .05) 1px, transparent 1px);
  background-size: 44px 44px;
}
/* 右上角品牌光晕 */
.login-left::after {
  content: "";
  position: absolute;
  width: 640px;
  height: 640px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(37, 99, 235, .28), transparent 65%);
  top: -180px;
  right: -160px;
}
.brand-inner { position: relative; z-index: 1; max-width: 440px; padding: 40px; }
.brand-logo { display: flex; align-items: center; gap: 14px; margin-bottom: 36px; }
.brand-logo svg { width: 46px; height: 46px; flex: none; }
.brand-logo .t1 { font-size: 24px; font-weight: 700; letter-spacing: .02em; }
.brand-logo .t2 { font-size: 12px; color: #8ea4c6; margin-top: 2px; }
.brand-slogan { font-size: 28px; font-weight: 600; line-height: 1.5; margin-bottom: 14px; }
.brand-slogan em { font-style: normal; color: #6ea8ff; }
.brand-desc { color: #8ea4c6; font-size: 14px; line-height: 1.9; margin-bottom: 40px; }
.brand-feats { display: flex; flex-direction: column; gap: 16px; }
.feat { display: flex; gap: 12px; align-items: flex-start; }
.feat-ico {
  width: 32px; height: 32px; border-radius: 8px;
  background: rgba(79, 140, 255, .14);
  border: 1px solid rgba(79, 140, 255, .25);
  display: flex; align-items: center; justify-content: center;
  font-size: 15px; flex: none;
}
.feat b { display: block; font-size: 13px; font-weight: 500; }
.feat span { font-size: 12px; color: #7d93b8; }

/* 右·登录区 */
.login-right { flex: 1; display: flex; align-items: center; justify-content: center; background: #f4f6fa; }
.login-card { width: 380px; }
.login-card h2 { font-size: 22px; font-weight: 600; color: #17233d; margin-bottom: 6px; }
.login-card .sub { color: #5c6b8a; margin-bottom: 28px; }
.field { margin-bottom: 18px; }
.field label { display: block; font-size: 12px; color: #5c6b8a; margin-bottom: 6px; }
.login-opt { display: flex; justify-content: space-between; align-items: center; margin-bottom: 22px; font-size: 12px; }
.link { color: #2563eb; cursor: pointer; }
.link:hover { color: #1d4ed8; }
.login-btn { width: 100%; height: 40px; font-size: 14px; font-weight: 600; letter-spacing: .3em; }
.login-foot { text-align: center; margin-top: 22px; font-size: 12px; color: #9aa7bf; }
</style>
