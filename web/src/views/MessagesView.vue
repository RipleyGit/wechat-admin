<template>
  <div class="page messages-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">粉丝私信</h1>
        <div class="page-subtitle">与粉丝一对一对话，受微信 48 小时互动窗口限制</div>
      </div>
      <div class="toolbar">
        <el-input v-model.trim="keyword" placeholder="搜索昵称或备注" clearable style="width:220px" @change="loadSessions" />
        <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <div class="surface im">
      <aside class="session-list" v-loading="sessionLoading">
        <el-scrollbar>
          <button
            v-for="item in sessions"
            :key="item.openid"
            class="session-item"
            :class="{ active: item.openid === activeOpenid }"
            @click="openSession(item)"
          >
            <el-avatar :size="38" :src="item.headimgurl">{{ displayName(item).slice(0, 1) }}</el-avatar>
            <span class="session-copy">
              <b>{{ displayName(item) }}</b>
              <small class="muted">{{ item.lastMsgSummary || '—' }}</small>
            </span>
            <span class="session-side">
              <small class="muted">{{ shortTime(item.lastMsgTime) }}</small>
              <el-badge v-if="item.unreadCount > 0" :value="item.unreadCount" :max="99" />
            </span>
          </button>
          <el-empty v-if="!sessionLoading && !sessions.length" description="还没有粉丝发来消息" :image-size="70" />
        </el-scrollbar>
        <div v-if="sessionTotal > sessions.length" class="session-more">
          <el-button text size="small" @click="loadMoreSessions">加载更多</el-button>
        </div>
      </aside>

      <section class="chat">
        <template v-if="activeOpenid">
          <header class="chat-head">
            <b>{{ displayName(activeSession) }}</b>
            <span :class="['window-tip', windowOpen ? 'ok' : 'expired']">
              {{ windowOpen ? `可回复，剩余 ${windowText}` : '超过 48 小时，微信不允许再发送消息' }}
            </span>
          </header>

          <el-scrollbar ref="timelineRef" class="timeline" v-loading="timelineLoading">
            <div v-if="nextCursor" class="load-earlier">
              <el-button text size="small" @click="loadEarlier">加载更早的消息</el-button>
            </div>
            <div v-for="msg in timeline" :key="msg.id" :class="['bubble-row', msg.inOut === 1 ? 'out' : 'in']">
              <div class="bubble">
                <template v-if="msg.msgType === 'text'">{{ msg.detail?.content }}</template>
                <template v-else-if="msg.msgType === 'image'">
                  <el-image
                    v-if="mediaSrc(msg)"
                    :src="mediaSrc(msg)"
                    :preview-src-list="[mediaSrc(msg)]"
                    preview-teleported
                    fit="cover"
                    class="bubble-image"
                  />
                  <span v-else class="media-pending">图片转存中…</span>
                </template>
                <!-- 视频同样只播自己转存的文件，微信的临时地址几天就失效 -->
                <template v-else-if="msg.msgType === 'video' || msg.msgType === 'shortvideo'">
                  <video v-if="mediaSrc(msg)" :src="mediaSrc(msg)" class="bubble-video" controls preload="metadata" />
                  <span v-else class="media-pending">视频转存中…</span>
                </template>
                <!-- 语音：音频转存到对象存储后由前端解码播放，detail.url 为空说明还没转存好或 MinIO 未配置 -->
                <VoiceBubble
                  v-else-if="msg.msgType === 'voice'"
                  :url="msg.detail?.url || ''"
                  :recognition="msg.detail?.recognition || ''"
                />
                <template v-else-if="msg.msgType === 'location'">
                  <span class="muted">[位置]</span> {{ msg.detail?.label }}
                </template>
                <template v-else-if="msg.msgType === 'link'">
                  <a :href="msg.detail?.url" target="_blank" rel="noopener noreferrer">{{ msg.detail?.title || msg.detail?.url }}</a>
                </template>
                <span v-else class="muted">[{{ msg.msgType }}]</span>
              </div>
              <small class="bubble-time muted">{{ shortTime(msg.createTime) }}</small>
            </div>
            <el-empty v-if="!timelineLoading && !timeline.length" description="暂无消息" :image-size="70" />
          </el-scrollbar>

          <footer class="composer">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="3"
              resize="none"
              maxlength="600"
              show-word-limit
              :disabled="!windowOpen"
              :placeholder="windowOpen ? '回车发送，Shift+回车换行' : '互动窗口已关闭'"
              @keydown.enter.exact.prevent="send"
            />
            <div class="composer-actions">
              <el-upload :show-file-list="false" :http-request="sendImage" accept="image/*" :disabled="!windowOpen">
                <el-button :icon="Picture" text :disabled="!windowOpen">图片</el-button>
              </el-upload>
              <!-- 窗口外触达预留位，后端 /reach 目前返回未启用，详见 docs/fan-messaging.md -->
              <el-button v-if="false" text>模板消息触达</el-button>
              <el-button type="primary" :loading="sending" :disabled="!windowOpen || !draft.trim()" @click="send">发送</el-button>
            </div>
          </footer>
        </template>
        <el-empty v-else description="选择左侧会话开始回复" />
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture, Refresh } from '@element-plus/icons-vue'
import { api } from '@/api/console'
import VoiceBubble from '@/components/VoiceBubble.vue'

/** 打开会话时的时间线轮询间隔 */
const TIMELINE_POLL_INTERVAL = 15000

const keyword = ref('')
const sessions = ref([])
const sessionTotal = ref(0)
const sessionPage = ref(1)
const sessionLoading = ref(false)

const activeOpenid = ref('')
const timeline = ref([])
const nextCursor = ref(null)
const timelineLoading = ref(false)
const timelineRef = ref(null)

const draft = ref('')
const sending = ref(false)
const remainingWindow = ref(0)

let timelineTimer = null
let countdownTimer = null

const activeSession = computed(() => sessions.value.find((s) => s.openid === activeOpenid.value) || {})
const windowOpen = computed(() => remainingWindow.value > 0)
const windowText = computed(() => {
  const total = Math.floor(remainingWindow.value / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  return h > 0 ? `${h} 小时 ${m} 分` : `${m} 分`
})

function displayName(item) {
  return item?.remark || item?.nickname || item?.openid || '未知粉丝'
}

function shortTime(value) {
  if (!value) return ''
  const d = new Date(value)
  const today = new Date()
  const sameDay = d.toDateString() === today.toDateString()
  const pad = (n) => String(n).padStart(2, '0')
  return sameDay
    ? `${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 只认自己转存到对象存储的 URL。
 * 微信的 picUrl 有防盗链：浏览器带 Referer 去取时，返回的是一张
 * "此图片来自微信公众平台 未经允许不可引用" 的占位图（实测 2.8KB，原图 500KB+）。
 * 回退到 picUrl 会显示一张看起来正常的假图，比直接说"转存中"更误导人。
 */
function mediaSrc(msg) {
  return msg.detail?.url || ''
}

// http 拦截器已经弹过错误提示了，这里只需要收住 reject，不然满控制台 unhandled rejection
async function loadSessions(reset = true) {
  if (reset) sessionPage.value = 1
  sessionLoading.value = true
  try {
    const r = await api.msgSessions({ keyword: keyword.value || undefined, page: sessionPage.value })
    sessions.value = reset ? r.list || [] : [...sessions.value, ...(r.list || [])]
    sessionTotal.value = r.total || 0
  } catch {
    if (reset) sessions.value = []
  } finally {
    sessionLoading.value = false
  }
}

async function loadMoreSessions() {
  sessionPage.value += 1
  await loadSessions(false)
}

/**
 * 入站媒体的转存是异步的：wx_msg 行先落库，detail.url 稍后才补上。
 * 首次渲染时 url 往往还是空的，而轮询只追加新消息、从不回看老行，
 * 所以那条消息会一直空着直到整页刷新。这里把已有行的 url 补回去。
 */
function patchMediaUrls(list) {
  const incoming = new Map()
  for (const m of list) {
    if (m.detail?.url) incoming.set(m.id, m.detail.url)
  }
  if (!incoming.size) return
  for (const m of timeline.value) {
    if (m.detail && !m.detail.url && incoming.has(m.id)) {
      m.detail.url = incoming.get(m.id)
    }
  }
}

/**
 * silent 用于轮询和发送后刷新：只把新消息接到末尾，不整体替换。
 * 整体替换会把"加载更早"翻出来的历史冲掉，滚动位置也会跳。
 */
async function loadTimeline({ silent = false } = {}) {
  if (!activeOpenid.value) return
  const openid = activeOpenid.value
  if (!silent) timelineLoading.value = true
  try {
    const r = await api.msgTimeline({ openid })
    // 轮询回来时用户可能已经切走了，别把别人的消息写进当前会话
    if (openid !== activeOpenid.value) return
    const list = r.list || []
    if (silent && timeline.value.length) {
      patchMediaUrls(list)
      const maxId = timeline.value.reduce((max, m) => (m.id > max ? m.id : max), 0)
      const fresh = list.filter((m) => m.id > maxId)
      if (!fresh.length) {
        remainingWindow.value = r.remainingWindowMillis || 0
        return
      }
      timeline.value = [...timeline.value, ...fresh]
    } else {
      timeline.value = list
      nextCursor.value = r.nextCursor || null
    }
    remainingWindow.value = r.remainingWindowMillis || 0
    scrollToBottom()
    await markRead()
  } catch {
    /* 拦截器已提示 */
  } finally {
    timelineLoading.value = false
  }
}

async function loadEarlier() {
  if (!nextCursor.value) return
  const openid = activeOpenid.value
  try {
    const r = await api.msgTimeline({ openid, beforeId: nextCursor.value })
    if (openid !== activeOpenid.value) return
    timeline.value = [...(r.list || []), ...timeline.value]
    nextCursor.value = r.nextCursor || null
  } catch {
    /* 拦截器已提示 */
  }
}

/** 水位线取当前渲染到的最大 id，标记失败只是红点还在，不影响看消息 */
async function markRead() {
  const maxId = timeline.value.reduce((max, m) => (m.id > max ? m.id : max), 0)
  if (!maxId) return
  try {
    await api.msgMarkRead({ openid: activeOpenid.value, lastReadMsgId: maxId })
    const item = sessions.value.find((s) => s.openid === activeOpenid.value)
    if (item) item.unreadCount = 0
  } catch {
    /* 拦截器已提示 */
  }
}

function scrollToBottom() {
  nextTick(() => timelineRef.value?.setScrollTop(timelineRef.value.wrapRef?.scrollHeight || 0))
}

async function openSession(item) {
  activeOpenid.value = item.openid
  draft.value = ''
  timeline.value = []
  nextCursor.value = null
  await loadTimeline()
}

async function send() {
  const content = draft.value.trim()
  if (!content || !windowOpen.value) return
  sending.value = true
  try {
    const form = new FormData()
    form.append('openid', activeOpenid.value)
    form.append('msgType', 'text')
    form.append('content', content)
    await api.sendMsg(form)
    // 只有发成功才清草稿，失败了得让人能改改再发
    draft.value = ''
    await afterSend()
  } catch {
    /* 拦截器已提示 */
  } finally {
    sending.value = false
  }
}

async function sendImage({ file }) {
  if (!windowOpen.value) return
  const form = new FormData()
  form.append('openid', activeOpenid.value)
  form.append('msgType', 'image')
  form.append('file', file)
  try {
    await api.sendMsg(form)
    ElMessage.success('已发送')
    await afterSend()
  } catch {
    /* 拦截器已提示 */
  }
}

/** 发完刷时间线和会话列表：会话列表的最后一条摘要和排序都变了 */
async function afterSend() {
  await loadTimeline({ silent: true })
  await loadSessions()
}

async function refreshAll() {
  await loadSessions()
  if (activeOpenid.value) await loadTimeline()
}

onMounted(() => {
  loadSessions()
  timelineTimer = setInterval(() => {
    if (document.hidden || !activeOpenid.value) return
    loadTimeline({ silent: true })
  }, TIMELINE_POLL_INTERVAL)
  // 倒计时本地递减，不用为了刷新数字去打接口
  countdownTimer = setInterval(() => {
    if (remainingWindow.value > 0) remainingWindow.value = Math.max(remainingWindow.value - 1000, 0)
  }, 1000)
})
onUnmounted(() => {
  clearInterval(timelineTimer)
  clearInterval(countdownTimer)
})
</script>

<style scoped>
.messages-page { display: flex; flex-direction: column; height: calc(100vh - 68px); }
.im { flex: 1; min-height: 0; display: grid; grid-template-columns: 300px 1fr; overflow: hidden; }
.session-list { border-right: 1px solid #e8ecf3; display: flex; flex-direction: column; min-height: 0; }
.session-list :deep(.el-scrollbar) { flex: 1; }
.session-item { width: 100%; border: 0; background: none; cursor: pointer; text-align: left; display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid #f2f5f9; }
.session-item:hover { background: #f7f9fc; }
.session-item.active { background: #eef4ff; }
.session-copy { flex: 1; min-width: 0; display: grid; gap: 3px; }
.session-copy b { font-size: 14px; font-weight: 600; }
.session-copy small, .session-side small { font-size: 12px; }
.session-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-side { display: grid; justify-items: end; gap: 6px; flex: 0 0 auto; }
.session-more { padding: 8px; text-align: center; border-top: 1px solid #f2f5f9; }
.chat { display: flex; flex-direction: column; min-height: 0; min-width: 0; }
.chat-head { height: 52px; flex: 0 0 52px; display: flex; align-items: center; justify-content: space-between; padding: 0 18px; border-bottom: 1px solid #e8ecf3; }
.window-tip { font-size: 12px; }
.window-tip.ok { color: #17a36c; }
.window-tip.expired { color: #d97706; }
.timeline { flex: 1; min-height: 0; padding: 16px 18px; background: #f7f9fc; }
.load-earlier { text-align: center; padding-bottom: 8px; }
.bubble-row { display: grid; justify-items: start; gap: 4px; margin-bottom: 14px; }
.bubble-row.out { justify-items: end; }
.bubble { max-width: 62%; padding: 9px 12px; border-radius: 8px; background: #fff; border: 1px solid #e8ecf3; font-size: 14px; line-height: 21px; word-break: break-word; white-space: pre-wrap; }
.bubble-row.out .bubble { background: #d7f0e2; border-color: #c2e7d3; }
.bubble-image { max-width: 220px; border-radius: 4px; }
.bubble-video { max-width: 240px; max-height: 320px; border-radius: 4px; display: block; background: #000; }
.media-pending { color: #a0aec0; font-size: 12px; }
.bubble-time { font-size: 11px; }
.composer { flex: 0 0 auto; border-top: 1px solid #e8ecf3; padding: 12px 18px; background: #fff; }
.composer-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.composer-actions > .el-button[type='primary'], .composer-actions > .el-button--primary { margin-left: auto; }
</style>
