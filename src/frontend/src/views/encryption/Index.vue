<template>
  <div class="page-container">
    <el-card>
      <!-- 工具栏：左侧选择应用/接口并新增配置，右侧查询 -->
      <div class="toolbar">
        <el-select v-model="appId" placeholder="请选择应用" filterable clearable style="width:240px" @change="onAppChange">
          <el-option v-for="item in appList" :key="item.id" :value="item.id" :label="item.appName" />
        </el-select>
        <el-button type="primary" @click="handleAdd">＋ 新增配置</el-button>
        <div class="spacer"></div>
        <el-button type="primary" @click="handleQuery">查询</el-button>
      </div>

      <el-tabs v-model="activeTab">
        <!-- ============ 应用级加密配置 ============ -->
        <el-tab-pane label="应用级加密配置" name="app">
          <el-form :model="appForm" label-width="130px" style="max-width:760px">
            <el-form-item label="加密算法">
              <el-select v-model="appForm.algorithm" placeholder="请选择加密算法" style="width:100%">
                <el-option label="SM4" value="SM4" /><el-option label="SM2" value="SM2" /><el-option label="AES" value="AES" />
              </el-select>
            </el-form-item>
            <el-form-item label="加密模式">
              <el-select v-model="appForm.mode" placeholder="请选择加密模式" style="width:100%">
                <el-option label="ECB" value="ECB" /><el-option label="CBC" value="CBC" /><el-option label="CFB" value="CFB" /><el-option label="CTR" value="CTR" />
              </el-select>
            </el-form-item>
            <el-form-item label="填充方式">
              <el-select v-model="appForm.padding" placeholder="请选择填充方式" style="width:100%">
                <el-option label="PKCS5Padding" value="PKCS5Padding" /><el-option label="PKCS7Padding" value="PKCS7Padding" /><el-option label="NoPadding" value="NoPadding" />
              </el-select>
            </el-form-item>
            <!-- 对称密钥：支持一键生成随机密钥 -->
            <el-form-item label="对称密钥">
              <el-input v-model="appForm.secretKey" placeholder="Base64 编码的对称密钥" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="generateSecret('appForm', 'secretKey')">生成</el-button>
            </el-form-item>
            <!-- IV 向量：ECB 模式无需 IV，可留空 -->
            <el-form-item label="IV 向量">
              <el-input v-model="appForm.iv" placeholder="Base64 编码的 IV，ECB 模式可留空" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="generateSecret('appForm', 'iv')">生成</el-button>
            </el-form-item>
            <!-- 非对称密钥对：SM2 场景使用 -->
            <el-form-item label="公钥">
              <el-input v-model="appForm.publicKey" type="textarea" :rows="3" placeholder="Base64 编码的公钥（SM2 场景填写）" />
            </el-form-item>
            <el-form-item label="私钥">
              <el-input v-model="appForm.privateKey" type="textarea" :rows="3" placeholder="Base64 编码的私钥（SM2 场景填写）" />
            </el-form-item>
            <el-form-item label="签名算法">
              <el-select v-model="appForm.signAlgorithm" placeholder="请选择签名算法" style="width:100%">
                <el-option label="SM3" value="SM3" /><el-option label="SHA256" value="SHA256" /><el-option label="MD5" value="MD5" />
              </el-select>
            </el-form-item>
            <el-form-item><el-button type="primary" :loading="saving" @click="saveAppConfig">保存</el-button></el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ============ 接口级加密配置 ============ -->
        <el-tab-pane label="接口级加密配置" name="interface">
          <el-select v-model="interfaceId" placeholder="请选择接口" filterable clearable style="width:420px" @change="onInterfaceChange">
            <el-option v-for="item in interfaceList" :key="item.id" :value="item.id" :label="item.interfaceName">
              <span>{{ item.interfaceName }}</span><span style="color:#909399;margin-left:8px">{{ item.interfacePath }}</span>
            </el-option>
          </el-select>

          <el-form :model="ifaceForm" label-width="130px" style="max-width:760px;margin-top:16px">
            <!-- 入参加密配置 -->
            <el-divider content-position="left">入参加密</el-divider>
            <el-form-item label="是否加密"><el-switch v-model="ifaceForm.requestEncrypted" /></el-form-item>
            <el-form-item label="加密算法">
              <el-select v-model="ifaceForm.requestAlgorithm" placeholder="空=不加密" clearable style="width:100%">
                <el-option label="空" value="" /><el-option label="SM4" value="SM4" /><el-option label="AES" value="AES" />
              </el-select>
            </el-form-item>
            <el-form-item label="加密模式">
              <el-select v-model="ifaceForm.requestMode" placeholder="请选择加密模式" style="width:100%">
                <el-option label="ECB" value="ECB" /><el-option label="CBC" value="CBC" /><el-option label="CFB" value="CFB" /><el-option label="CTR" value="CTR" />
              </el-select>
            </el-form-item>
            <el-form-item label="密钥">
              <el-input v-model="ifaceForm.requestKey" placeholder="Base64 编码的密钥" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="generateSecret('ifaceForm', 'requestKey')">生成</el-button>
            </el-form-item>
            <el-form-item label="IV 向量">
              <el-input v-model="ifaceForm.requestIv" placeholder="ECB 模式可留空" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="generateSecret('ifaceForm', 'requestIv')">生成</el-button>
            </el-form-item>
            <el-form-item label="填充方式">
              <el-select v-model="ifaceForm.requestPadding" placeholder="请选择填充方式" style="width:100%">
                <el-option label="PKCS5Padding" value="PKCS5Padding" /><el-option label="PKCS7Padding" value="PKCS7Padding" /><el-option label="NoPadding" value="NoPadding" />
              </el-select>
            </el-form-item>

            <!-- 返参加密配置 -->
            <el-divider content-position="left">返参加密</el-divider>
            <el-form-item label="是否加密"><el-switch v-model="ifaceForm.responseEncrypted" /></el-form-item>
            <el-form-item label="加密算法">
              <el-select v-model="ifaceForm.responseAlgorithm" placeholder="空=不加密" clearable style="width:100%">
                <el-option label="空" value="" /><el-option label="SM4" value="SM4" /><el-option label="AES" value="AES" />
              </el-select>
            </el-form-item>
            <el-form-item label="加密模式">
              <el-select v-model="ifaceForm.responseMode" placeholder="请选择加密模式" style="width:100%">
                <el-option label="ECB" value="ECB" /><el-option label="CBC" value="CBC" /><el-option label="CFB" value="CFB" /><el-option label="CTR" value="CTR" />
              </el-select>
            </el-form-item>
            <el-form-item label="密钥">
              <el-input v-model="ifaceForm.responseKey" placeholder="Base64 编码的密钥" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="generateSecret('ifaceForm', 'responseKey')">生成</el-button>
            </el-form-item>
            <el-form-item label="IV 向量">
              <el-input v-model="ifaceForm.responseIv" placeholder="ECB 模式可留空" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="generateSecret('ifaceForm', 'responseIv')">生成</el-button>
            </el-form-item>
            <el-form-item label="填充方式">
              <el-select v-model="ifaceForm.responsePadding" placeholder="请选择填充方式" style="width:100%">
                <el-option label="PKCS5Padding" value="PKCS5Padding" /><el-option label="PKCS7Padding" value="PKCS7Padding" /><el-option label="NoPadding" value="NoPadding" />
              </el-select>
            </el-form-item>
            <el-form-item><el-button type="primary" :loading="saving" @click="saveInterfaceConfig">保存</el-button></el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script>
import { getAppList, getInterfaceList, getInterfaceEncryptionConfig, saveInterfaceEncryptionConfig, getAppEncryptionConfig, saveAppEncryptionConfig } from '@/api/modules'

export default {
  data() {
    return {
      // 当前标签页：app=应用级，interface=接口级
      activeTab: 'app',
      // 应用下拉数据、接口下拉数据
      appList: [], interfaceList: [],
      // 当前选中的应用 ID 与接口 ID
      appId: null, interfaceId: null,
      // 加载与保存状态（下拉列表加载 / 配置保存防重）
      loading: false, saving: false,
      // 应用级加密配置表单
      appForm: { appId: null, algorithm: '', mode: '', padding: '', secretKey: '', iv: '', publicKey: '', privateKey: '', signAlgorithm: '' },
      // 接口级加密配置表单（入参 / 返参各一套）
      ifaceForm: {
        interfaceId: null,
        requestEncrypted: false, requestAlgorithm: '', requestMode: '', requestKey: '', requestIv: '', requestPadding: '',
        responseEncrypted: false, responseAlgorithm: '', responseMode: '', responseKey: '', responseIv: '', responsePadding: ''
      }
    }
  },
  mounted() { this.loadAppList(); this.loadInterfaceList() },
  methods: {
    // 加载应用下拉数据
    async loadAppList() {
      this.loading = true
      try {
        const res = await getAppList({ current: 1, size: 100 })
        this.appList = res.data.records || []
      } catch (e) { this.$message.error('应用列表加载失败') } finally { this.loading = false }
    },
    // 加载接口下拉数据
    async loadInterfaceList() {
      this.loading = true
      try {
        const res = await getInterfaceList({ current: 1, size: 100 })
        this.interfaceList = res.data.records || []
      } catch (e) { this.$message.error('接口列表加载失败') } finally { this.loading = false }
    },
    // 生成一份空的应用级配置（保留所有字段，保证 Vue 响应式）
    emptyAppForm() {
      return { appId: this.appId, algorithm: '', mode: '', padding: '', secretKey: '', iv: '', publicKey: '', privateKey: '', signAlgorithm: '' }
    },
    // 生成一份空的接口级配置（保留所有字段，保证 Vue 响应式）
    emptyIfaceForm() {
      return {
        interfaceId: this.interfaceId,
        requestEncrypted: false, requestAlgorithm: '', requestMode: '', requestKey: '', requestIv: '', requestPadding: '',
        responseEncrypted: false, responseAlgorithm: '', responseMode: '', responseKey: '', responseIv: '', responsePadding: ''
      }
    },
    // 切换应用：自动加载该应用的加密配置，无配置则表单留空待新建
    async onAppChange(val) {
      this.appForm = this.emptyAppForm()
      if (!val) return
      try {
        const res = await getAppEncryptionConfig(val)
        if (res.data) this.appForm = { ...this.emptyAppForm(), ...res.data, appId: val }
      } catch (e) { this.$message.error('应用加密配置加载失败') }
    },
    // 切换接口：自动加载该接口的加密配置，无配置则表单留空待新建
    async onInterfaceChange(val) {
      this.ifaceForm = this.emptyIfaceForm()
      if (!val) return
      try {
        const res = await getInterfaceEncryptionConfig(val)
        if (res.data) this.ifaceForm = { ...this.emptyIfaceForm(), ...res.data, interfaceId: val }
      } catch (e) { this.$message.error('接口加密配置加载失败') }
    },
    // 新增配置：清空当前标签页的表单，重新填写一份
    handleAdd() {
      if (this.activeTab === 'app') {
        if (!this.appId) return this.$message.warning('请先选择应用')
        this.appForm = this.emptyAppForm()
      } else {
        if (!this.interfaceId) return this.$message.warning('请先选择接口')
        this.ifaceForm = this.emptyIfaceForm()
      }
    },
    // 查询：刷新下拉数据并重新加载当前选中对象的配置
    handleQuery() {
      this.loadAppList(); this.loadInterfaceList()
      if (this.appId) this.onAppChange(this.appId)
      if (this.interfaceId) this.onInterfaceChange(this.interfaceId)
    },
    // 生成随机密钥：16 字节随机数转 Base64，回填到指定表单字段
    generateSecret(form, field) {
      const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256))
      this[form][field] = btoa(String.fromCharCode.apply(null, bytes))
      this.$message.success('已生成随机密钥')
    },
    // 保存应用级加密配置
    async saveAppConfig() {
      if (this.saving) return
      if (!this.appId) return this.$message.warning('请先选择应用')
      this.saving = true
      try {
        await saveAppEncryptionConfig({ ...this.appForm, appId: this.appId })
        this.$message.success('保存成功')
        this.onAppChange(this.appId)
      } catch (e) { this.$message.error('保存失败') } finally { this.saving = false }
    },
    // 保存接口级加密配置
    async saveInterfaceConfig() {
      if (this.saving) return
      if (!this.interfaceId) return this.$message.warning('请先选择接口')
      this.saving = true
      try {
        await saveInterfaceEncryptionConfig({ ...this.ifaceForm, interfaceId: this.interfaceId })
        this.$message.success('保存成功')
        this.onInterfaceChange(this.interfaceId)
      } catch (e) { this.$message.error('保存失败') } finally { this.saving = false }
    }
  }
}
</script>
