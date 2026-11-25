<template>
  <div class="channel-info">
    <div v-loading="loading" class="card">
      <template v-if="error">
        <el-result icon="warning" title="无法读取通道信息" :sub-title="error" />
      </template>

      <template v-else-if="info">
        <header>
          <h1>{{ info.name || '未命名通道' }}</h1>
          <div class="meta">
            <el-tag size="small" :type="info.sendType === 'template' ? 'warning' : 'success'">
              {{ info.sendType === 'template' ? '模板消息' : '客服消息' }}
            </el-tag>
            <span class="mono">{{ info.code }}</span>
          </div>
        </header>

        <section class="gateway">
          <div class="label">网关地址</div>
          <code>{{ gatewayUrl }}</code>
          <el-button size="small" text type="primary" @click="copy(gatewayUrl)">复制</el-button>
        </section>

        <template v-if="info.sendType === 'template'">
          <dl>
            <div>
              <dt>模板 ID</dt>
              <dd class="mono">{{ info.templateId || '未选择模板' }}</dd>
              <el-button v-if="info.templateId" size="small" text type="primary"
                         @click="copy(info.templateId)">复制</el-button>
            </div>
            <div v-if="info.templateTitle">
              <dt>模板标题</dt>
              <dd>{{ info.templateTitle }}</dd>
            </div>
            <div v-if="info.templateUrl">
              <dt>跳转链接</dt>
              <dd class="mono">{{ info.templateUrl }}</dd>
            </div>
          </dl>

          <section v-if="info.templateContent" class="block">
            <div class="label">模板正文</div>
            <pre class="content">{{ info.templateContent }}</pre>
          </section>

          <section class="block">
            <div class="label">字段映射</div>
            <ul v-if="mapping.length" class="mapping">
              <li v-for="(item, index) in mapping" :key="index">
                <code>{{ item.name }}</code>
                <span>{{ item.value }}</span>
              </li>
            </ul>
            <div v-else class="empty">该通道没有配置字段映射，调用方可以完全自定义内容</div>
          </section>
        </template>

        <section v-else class="block">
          <div class="label">文案模板</div>
          <pre v-if="info.contentTemplate" class="content">{{ info.contentTemplate }}</pre>
          <div v-else class="empty">该通道没有配置文案模板，内容由调用方传入</div>
        </section>

        <p class="tip">
          二维码 10 分钟内有效，过期在运营台重新点一次「二维码」。
          网关调用需要请求头 <code>X-Notify-Secret</code>，密钥在公众号配置里生成。
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

const gatewayUrl = computed(() => `${window.location.origin}/wx/notify/${info.value?.code || ''}`)

const mapping = computed(() => {
  const raw = info.value?.templateData
  return Array.isArray(raw) ? raw : []
})

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请长按手动复制')
  }
}

onMounted(async () => {
  try {
    const r = await api.channelInfo(route.params.token)
    info.value = r.data
  } catch (e) {
    error.value = e.message || '二维码已失效'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.channel-info {
  min-height: 100vh;
  padding: 32px 16px;
  background: #f4f6f9;
  display: flex;
  justify-content: flex-start;
  align-items: flex-start;
}
.card {
  width: 100%;
  max-width: 640px;
  min-height: 200px;
  margin: 0 auto;
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
.label {
  color: #73809a;
  font-size: 13px;
  margin-bottom: 6px;
}
.gateway {
  margin-top: 20px;
  padding: 14px;
  background: #f7fbf8;
  border: 1px solid #dfe7e3;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.gateway .label {
  width: 100%;
  margin-bottom: 2px;
}
.gateway code {
  flex: 1;
  min-width: 0;
  color: #195e40;
  font-size: 12px;
  word-break: break-all;
}
dl {
  margin: 18px 0 0;
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
.block {
  margin-top: 20px;
}
.content {
  margin: 0;
  padding: 12px;
  background: #f7f8fa;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.8;
  color: #273248;
  white-space: pre-wrap;
  word-break: break-all;
}
.mapping {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid #edf0f5;
  border-radius: 8px;
  overflow: hidden;
}
.mapping li {
  display: flex;
  gap: 12px;
  padding: 10px 12px;
  border-top: 1px solid #edf0f5;
  font-size: 13px;
  align-items: flex-start;
}
.mapping li:first-child {
  border-top: 0;
}
.mapping code {
  flex: none;
  min-width: 96px;
  color: #195e40;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.mapping span {
  flex: 1;
  color: #273248;
  word-break: break-all;
}
.empty {
  color: #97a1b4;
  font-size: 13px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
}
.tip {
  margin: 20px 0 0;
  color: #8793a9;
  font-size: 12px;
  line-height: 1.7;
}
.tip code {
  color: #195e40;
}
</style>
