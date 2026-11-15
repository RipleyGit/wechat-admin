<template>
  <div class="push-info">
    <div v-loading="loading" class="card">
      <template v-if="error">
        <el-result icon="warning" title="无法读取接入信息" :sub-title="error" />
      </template>

      <template v-else-if="info">
        <header>
          <h1>{{ info.name }}</h1>
          <div class="meta">
            <el-tag size="small" :type="info.type === 1 ? 'success' : 'info'">{{ typeLabel }}</el-tag>
            <span>{{ info.appid }}</span>
          </div>
        </header>

        <dl>
          <div>
            <dt>公众号名称</dt>
            <dd>{{ info.name }}</dd>
          </div>
          <div>
            <dt>公众号类型</dt>
            <dd>{{ typeLabel }}</dd>
          </div>
          <div>
            <dt>AppID</dt>
            <dd class="mono">{{ info.appid }}</dd>
          </div>
          <div>
            <dt>推送地址</dt>
            <dd class="mono">{{ pushUrl }}</dd>
            <el-button size="small" text type="primary" @click="copy(pushUrl, '推送地址')">复制</el-button>
          </div>
          <div>
            <dt>推送密钥</dt>
            <dd class="mono">{{ info.notifySecret || '尚未生成' }}</dd>
            <el-button v-if="info.notifySecret" size="small" text type="primary"
                       @click="copy(info.notifySecret, '推送密钥')">复制</el-button>
          </div>
        </dl>

        <div class="usage">
          <p>调用时把密钥放在请求头：</p>
          <code>X-Notify-Secret: {{ info.notifySecret || '<推送密钥>' }}</code>
        </div>

        <p class="tip">
          密钥等同于该公众号的消息发送权限，请勿转发给无关人员。
          本页仅对本次扫码有效，关闭后如需再次查看，请在运营台重新生成二维码。
        </p>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api/console'

const route = useRoute()
const loading = ref(true)
const error = ref('')
const info = ref(null)

const typeLabel = computed(() => {
  if (info.value?.type === 1) return '服务号'
  if (info.value?.type === 2) return '订阅号'
  return '公众号'
})

// 页面与接口同源，推送地址按当前浏览器地址拼，生产环境就是 https://wechat.bleem.site/wx/push/template
const pushUrl = computed(() => {
  const apiOrigin = import.meta.env.VITE_API_ORIGIN || '/wx'
  return new URL(`${apiOrigin}/push/template`, window.location.origin).href
})

async function copy(text, label) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.warning('复制失败，请长按手动复制')
  }
}

onMounted(async () => {
  try {
    const r = await api.pushInfo(route.params.token)
    info.value = r.data
  } catch (e) {
    error.value = e.message || '二维码已失效'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.push-info {
  min-height: 100vh;
  padding: 32px 16px;
  background: #f4f6f9;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}
.card {
  width: 100%;
  max-width: 560px;
  min-height: 200px;
  padding: 24px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}
header h1 {
  margin: 0;
  font-size: 22px;
  color: #273248;
}
.meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  color: #8793a9;
  font-size: 13px;
}
dl {
  margin: 20px 0 0;
}
dl > div {
  display: grid;
  grid-template-columns: 84px 1fr auto;
  align-items: start;
  gap: 8px;
  padding: 12px 0;
  border-top: 1px solid #edf0f5;
}
dt {
  color: #73809a;
  font-size: 13px;
}
dd {
  margin: 0;
  color: #273248;
  font-size: 14px;
  word-break: break-all;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
}
.usage {
  margin-top: 16px;
  padding: 14px;
  background: #f7fbf8;
  border: 1px solid #dfe7e3;
  border-radius: 8px;
}
.usage p {
  margin: 0 0 8px;
  color: #66748d;
  font-size: 13px;
}
.usage code {
  display: block;
  color: #195e40;
  font-size: 12px;
  word-break: break-all;
}
.tip {
  margin: 16px 0 0;
  color: #8793a9;
  font-size: 12px;
  line-height: 1.7;
}
</style>
