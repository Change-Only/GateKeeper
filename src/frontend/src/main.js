// 引入 Vue 核心库
import Vue from 'vue'
// 引入 Element UI 组件库
import ElementUI from 'element-ui'
// 引入 Element UI 默认主题样式
import 'element-ui/lib/theme-chalk/index.css'
// 引入根组件
import App from './App.vue'
// 引入路由配置
import router from './router'
// 引入 Vuex 状态管理
import store from './store'
// 引入全局样式
import './assets/global.scss'
// 引入统一空态组件并全局注册
import EmptyState from './components/EmptyState.vue'

// 可复用构件（T05 §4）全局注册，供各页面直接使用
import CrudTable from '@/components/common/CrudTable.vue'
import CrudDialog from '@/components/common/CrudDialog.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import DictSelect from '@/components/common/DictSelect.vue'
import PermButton from '@/components/common/PermButton.vue'
import RoutePreview from '@/components/common/RoutePreview.vue'
import ThresholdEditor from '@/components/common/ThresholdEditor.vue'
import VersionRouteViz from '@/components/common/VersionRouteViz.vue'

// 权限点校验工具
import { hasPerm } from '@/utils/perm'

// 全局注册 Element UI 组件，并设置组件默认尺寸为 medium
Vue.use(ElementUI, { size: 'medium' })
// 全局注册空态组件，供各页面 el-table 的 empty 插槽使用
Vue.component('EmptyState', EmptyState)
// 全局注册 T05 复用构件
Vue.component('CrudTable', CrudTable)
Vue.component('CrudDialog', CrudDialog)
Vue.component('StatusTag', StatusTag)
Vue.component('DictSelect', DictSelect)
Vue.component('PermButton', PermButton)
Vue.component('RoutePreview', RoutePreview)
Vue.component('ThresholdEditor', ThresholdEditor)
Vue.component('VersionRouteViz', VersionRouteViz)

// 全局权限点校验方法：this.$hasPerm('grant:revoke')
Vue.prototype.$hasPerm = (code) => hasPerm(code, store.state.perms)

// 关闭 Vue 生产环境提示
Vue.config.productionTip = false

// 创建根 Vue 实例并挂载到 #app 节点
new Vue({
  router,
  store,
  render: h => h(App)
}).$mount('#app')
