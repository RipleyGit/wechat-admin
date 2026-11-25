import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [
      vue(),
      // Element Plus 只注册模板里真正用到的组件。
      // 之前在 main.js 里 .use(ElementPlus) 全量注册，整个库被推进主包：
      // 1.06 MB 未压缩 / 348 KB gzip，每个页面首屏都得先下载并解析完它才能交互。
      // dirs 置空：项目里的组件都是显式 import，不需要按目录自动注册
      Components({
        dirs: [],
        dts: false,
        resolvers: [ElementPlusResolver()],
      }),
    ],
    resolve: { alias: { '@': new URL('./src', import.meta.url).pathname } },
    build: {
      rollupOptions: {
        output: {
          // 把第三方库从主包剥出去：主包变成纯业务代码，体积小、随业务变；
          // 库代码 hash 稳定，能被浏览器长期缓存，二次访问不再重复下载解析
          manualChunks(id) {
            if (!id.includes('node_modules')) return
            // 语音解码库 440 KB，VoiceBubble 里是点击时才 import 的。
            // 必须显式排除，否则拆包会把它变成首屏必下载，抵消掉懒加载
            if (id.includes('benz-amr-recorder')) return
            return id.includes('element-plus') ? 'element-plus' : 'vendor'
          },
        },
      },
    },
    server: {
      port: 3001,
      proxy: {
        '/wx': {
          target: env.VITE_PROXY_TARGET,
          changeOrigin: true,
        },
      },
    },
  }
})