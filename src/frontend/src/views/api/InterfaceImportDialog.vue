<template>
  <el-dialog
    title="导入 OpenAPI 3.0 文档"
    :visible.sync="dialogVisible"
    width="760px"
    :close-on-click-modal="false"
    append-to-body
    @closed="reset"
  >
    <!-- ============ 结果态：导入完成后替换表单，直接给「导了几条、哪条没进来」 ============ -->
    <div v-if="result" class="import-result">
      <el-alert
        :type="alertType"
        :closable="false"
        show-icon
        :title="summaryTitle"
      >
        <div class="result-meta">
          <span>文档：<b>{{ result.title || '（无标题）' }}</b></span>
          <span>OpenAPI：<code>{{ result.openapiVersion }}</code></span>
          <span v-if="result.specVersion">文档版本：<code>{{ result.specVersion }}</code></span>
          <span>导入到分组：<b>{{ result.groupName || result.groupId }}</b></span>
        </div>
      </el-alert>

      <div class="result-stats">
        <div class="stat"><span class="num">{{ result.total }}</span><span class="lbl">文档接口数</span></div>
        <div class="stat ok"><span class="num">{{ result.imported }}</span><span class="lbl">已导入</span></div>
        <div class="stat warn"><span class="num">{{ result.skipped }}</span><span class="lbl">已跳过</span></div>
        <div class="stat err"><span class="num">{{ result.failed }}</span><span class="lbl">失败</span></div>
        <div class="stat"><span class="num">{{ result.paramCount }}</span><span class="lbl">写入参数</span></div>
      </div>

      <el-table :data="result.items" size="mini" border max-height="260" class="result-table">
        <el-table-column label="方法" width="72">
          <template #default="{ row }">
            <span class="method" :class="(row.method || 'GET').toLowerCase()">{{ row.method }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路径" min-width="180" show-overflow-tooltip />
        <el-table-column prop="name" label="接口名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="结果" width="86" align="center">
          <template #default="{ row }">
            <el-tag :type="tagTypeOf(row.result)" size="mini">{{ labelOf(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="paramCount" label="参数" width="60" align="center" />
        <el-table-column prop="message" label="说明" min-width="200" show-overflow-tooltip />
      </el-table>

      <ul v-if="result.warnings && result.warnings.length" class="result-warnings">
        <li v-for="(w, i) in result.warnings" :key="i"><i class="el-icon-info" /> {{ w }}</li>
      </ul>
    </div>

    <!-- ============ 表单态 ============ -->
    <div v-else>
      <el-alert type="info" :closable="false" show-icon class="tip-alert">
        <template #title>
          支持 <code>openapi: 3.x</code>（Swagger 3.0）的 <b>JSON</b> 或 <b>YAML</b> 文档。
          Swagger 2.0 请先用 swagger2openapi 转换后再导入。
        </template>
      </el-alert>

      <el-form ref="form" :model="form" :rules="rules" label-width="96px" class="import-form">
        <el-form-item label="目标分组" prop="groupId">
          <el-cascader
            v-model="form.groupId"
            :options="groupTree"
            :props="cascaderProps"
            placeholder="请选择接口所属分组（必填）"
            clearable
            style="width: 100%"
          />
          <div class="field-hint">
            导入的接口全部归入该分组。分组是环境配置的继承链起点，<b>未选择分组不允许导入</b>。
          </div>
        </el-form-item>

        <el-form-item label="文档文件">
          <el-upload
            action="#"
            :auto-upload="false"
            :show-file-list="false"
            accept=".json,.yaml,.yml"
            :on-change="onFileChange"
          >
            <el-button size="small" icon="el-icon-upload2">选择文件</el-button>
          </el-upload>
          <span v-if="fileName" class="file-name"><i class="el-icon-document" /> {{ fileName }}</span>
          <span v-else class="field-hint inline">未选择文件，可在下方直接粘贴文档内容</span>
        </el-form-item>

        <el-form-item label="文档内容" prop="content">
          <el-input
            v-model="content"
            type="textarea"
            :rows="8"
            :placeholder="PLACEHOLDER"
          />
          <div class="field-hint">
            选择文件后此处会自动填充，也可直接粘贴。单份文档上限 8MB、最多 500 个接口。
          </div>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <template v-if="result">
        <el-button @click="dialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="backToForm">继续导入</el-button>
      </template>
      <template v-else>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!form.groupId || !content"
          @click="submit"
        >
          开始导入
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script>
/**
 * OpenAPI 3.0 文档导入弹窗（T18）。
 *
 * <h3>为什么单独一个组件而不是塞进 ApiList</h3>
 * ApiList 已经有「新建/编辑弹窗 + 详情抽屉 + 测试弹窗」三层弹层，
 * 再往里加一个带结果明细表的导入弹窗会让单文件超 600 行，改一处要读全篇。
 *
 * <h3>需求硬约束的落点：「导入时必须先选择分组」</h3>
 * <ol>
 *   <li>分组选择器带 <b>必填</b> 校验规则（触发时机 change）；</li>
 *   <li>「开始导入」按钮 <b>未选分组即禁用</b>（<code>:disabled="!form.groupId"</code>），
 *       用户不用点下去才知道缺东西；</li>
 *   <li>后端 {@code POST /interface/import} 独立再拦一道并返回 400 ——
 *       前端闸门永远只是体验层，脚本可绕。</li>
 * </ol>
 *
 * <h3>文件读取为什么走 FileReader 而不是 multipart 上传</h3>
 * 后端只收「groupId + 文本正文」，于是「选文件」与「粘贴」共用同一条链路；
 * 而且 .json/.yaml 一律按 UTF-8 文本读，不必在后端再分辨编码。
 */
import { importOpenApi } from '@/api/modules'

export default {
  name: 'InterfaceImportDialog',
  props: {
    /** 弹窗显隐（.sync） */
    visible: { type: Boolean, default: false },
    /** 分组树（复用列表页已加载的 /group/tree，避免重复请求） */
    groupTree: { type: Array, default: () => [] }
  },
  data() {
    return {
      // 与列表页筛选、CrudDialog 的 tree-select 保持同一套 cascader 口径：
      // emitPath:false ⇒ v-model 直接是分组 id（单值）；checkStrictly:true ⇒ 任意层级可选
      cascaderProps: { value: 'id', label: 'groupName', children: 'children', checkStrictly: true, emitPath: false },
      form: { groupId: null },
      rules: {
        groupId: [{ required: true, message: '请先选择目标分组', trigger: 'change' }]
      },
      content: '',
      fileName: '',
      submitting: false,
      /** 导入结果；非 null 时界面切到结果态 */
      result: null,
      PLACEHOLDER: '在此粘贴 OpenAPI 3.0 文档内容（JSON 或 YAML），例如：\n{\n  "openapi": "3.0.1",\n  "info": { "title": "订单服务", "version": "1.0.0" },\n  "paths": { }\n}'
    }
  },
  computed: {
    dialogVisible: {
      get() { return this.visible },
      set(v) { this.$emit('update:visible', v) }
    },
    alertType() {
      if (!this.result) return 'info'
      if (this.result.failed > 0) return 'warning'
      if (this.result.imported === 0) return 'warning'
      return 'success'
    },
    summaryTitle() {
      if (!this.result) return ''
      const r = this.result
      if (r.imported === 0 && r.skipped > 0) {
        return `没有新增接口：${r.skipped} 条全部已存在（同一份文档重复导入不会产生重复接口）`
      }
      let s = `成功导入 ${r.imported} 个接口、${r.paramCount} 条参数`
      if (r.skipped > 0) s += `；${r.skipped} 条因已存在被跳过`
      if (r.failed > 0) s += `；${r.failed} 条失败（见下表说明）`
      return s
    }
  },
  methods: {
    /**
     * 选中文件后读成文本。
     *
     * <p>刻意在前端先做一次后缀与体积拦截：8MB 的正文走请求体传过去，
     * 让后端解析完再报"太大"是浪费；而且 .docx 之类的文件被当成文本读
     * 会得到一堆乱码，不拦会让人误以为"文档格式不支持"。</p>
     */
    onFileChange(file) {
      const raw = file && file.raw
      if (!raw) return
      const name = raw.name || ''
      if (!/\.(json|ya?ml)$/i.test(name)) {
        this.$message.error('仅支持 .json / .yaml / .yml 文件')
        return
      }
      if (raw.size > 2 * 1024 * 1024) {
        this.$message.error('文件过大（上限 2MB）：请拆分文档后分批导入')
        return
      }
      const reader = new FileReader()
      reader.onload = () => {
        this.content = String(reader.result || '')
        this.fileName = name
      }
      reader.onerror = () => this.$message.error('文件读取失败，请改用粘贴方式')
      reader.readAsText(raw, 'utf-8')
    },
    submit() {
      this.$refs.form.validate(async (ok) => {
        if (!ok) return
        this.submitting = true
        try {
          const res = await importOpenApi({
            groupId: this.form.groupId,
            content: this.content,
            fileName: this.fileName || null
          })
          this.result = res.data
          // 让列表页刷新：即便全是 skipped，也要刷新（分类名/新行可能已变）
          this.$emit('imported', this.result)
        } catch (e) {
          /* 响应拦截器已统一提示（未选分组 / 非 3.x / 文档解析失败均为 400） */
        } finally {
          this.submitting = false
        }
      })
    },
    /** 从结果态回到表单态继续导入下一份文档（分组保留，内容清空） */
    backToForm() {
      this.result = null
      this.content = ''
      this.fileName = ''
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    reset() {
      this.result = null
      this.content = ''
      this.fileName = ''
      this.form.groupId = null
      this.submitting = false
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    tagTypeOf(r) {
      if (r === 'IMPORTED') return 'success'
      if (r === 'SKIPPED') return 'warning'
      return 'danger'
    },
    labelOf(r) {
      if (r === 'IMPORTED') return '已导入'
      if (r === 'SKIPPED') return '已跳过'
      return '失败'
    }
  }
}
</script>

<style scoped>
.import-form { margin-top: 12px; }
.tip-alert { margin-top: 4px; }
.tip-alert code { background: rgba(64, 110, 220, 0.08); padding: 0 3px; border-radius: 3px; }
.field-hint { font-size: 11px; color: #9aa7bf; line-height: 1.6; margin-top: 2px; }
.field-hint.inline { margin-left: 10px; }
.file-name { margin-left: 10px; font-size: 12px; color: #17233d; }

/* ============ 结果态 ============ */
.import-result { display: flex; flex-direction: column; gap: 12px; }
.result-meta { display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 12px; margin-top: 4px; }
.result-meta code { background: rgba(64, 110, 220, 0.08); padding: 0 3px; border-radius: 3px; }
.result-stats { display: flex; gap: 10px; }
.stat {
  flex: 1; display: flex; flex-direction: column; align-items: center;
  padding: 8px 4px; border: 1px solid #e8eefb; border-radius: 6px; background: #fafcff;
}
.stat .num { font-size: 19px; font-weight: 600; color: #17233d; line-height: 1.2; }
.stat .lbl { font-size: 11px; color: #9aa7bf; margin-top: 2px; }
.stat.ok .num { color: #1a9c62; }
.stat.warn .num { color: #c8911a; }
.stat.err .num { color: #c03337; }
.result-table { border-radius: 6px; }
.result-warnings { margin: 0; padding-left: 4px; list-style: none; }
.result-warnings li { font-size: 12px; color: #5c6b8a; line-height: 1.7; }
.result-warnings li i { color: #409eff; margin-right: 4px; }
</style>
