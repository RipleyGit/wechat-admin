import { createApp } from 'vue'
// 样式仍用全量：ElMessage / ElMessageBox / ElNotification 这类命令式 API
// 走的是显式 import，不进模板，按需插件管不到它们的样式，全量 CSS 是唯一不漏的做法。
// JS 不在这引入了——组件由 vite.config.js 里的 unplugin-vue-components 按需注册
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import router from './router'

createApp(App).use(router).mount('#app')