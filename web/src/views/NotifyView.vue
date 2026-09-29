<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">消息推送</h1>
        <div class="page-subtitle">配置通道后，机器可以 POST 网关地址触发推送，也能在这里手动发</div>
      </div>
      <div class="head-actions">
        <el-button @click="openLogs()">推送记录</el-button>
        <el-button type="primary" @click="edit({})">新建通道</el-button>
      </div>
    </div>

    <div class="surface table-surface">
      <el-table :data="list" v-loading="loading">
        <el-table-column prop="name" label="通道名称" min-width="140" />
        <el-table-column prop="code" label="标识" min-width="130">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="发送方式" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.sendType === 'template' ? 'warning' : 'success'">
              {{ row.sendType === 'template' ? '模板消息' : '客服消息' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="收件人" min-width="160">
          <template #default="{ row }">
            <span class="muted">{{ row.recipientType === 'tag' ? '标签' : '指定' }}</span>
            {{ row.recipientValue }}
          </template>
        </el-table-column>
        <el-table-column label="文案来源" width="100">
          <template #default="{ row }">{{ row.contentMode === 'passthrough' ? '调用方传入' : '通道模板' }}</template>
        </el-table-column>
        <el-table-column label="接口调用" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.gatewayEnabled ? 'success' : 'info'">
              {{ row.gatewayEnabled ? '允许' : '未开放' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button link type="primary" @click="openSend(row)">发送</el-button>
            <el-button link type="primary" @click="openLogs(row)">记录</el-button>
            <el-dropdown @command="(c) => moreAction(c, row)">
              <el-button link type="primary">更多<el-icon><ArrowDown /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="curl">复制 curl 示例</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除通道</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination layout="prev,pager,next" :total="total" :page-size="params.limit"
                       :current-page="params.page" @current-change="(p) => { params.page = p; load() }" />
      </div>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑通道' : '新建通道'" width="720">
      <el-form label-width="96">
        <el-form-item label="通道名称">
          <el-input v-model="form.name" placeholder="如 部署成功通知" />
        </el-form-item>
        <el-form-item label="通道标识">
          <el-input v-model="form.code" :disabled="!!form.id" placeholder="字母数字下划线短横线，用作网关地址" />
          <div class="hint">网关地址：<span class="mono">POST {{ origin }}/wx/notify/{{ form.code || '{标识}' }}</span></div>
          <div v-if="form.id" class="hint">标识是网关地址的一部分，建好之后不能改，否则调用方那边会静默失败</div>
        </el-form-item>
        <el-form-item label="发送方式">
          <el-radio-group v-model="form.sendType">
            <el-radio label="kefu">客服消息</el-radio>
            <el-radio label="template">模板消息</el-radio>
          </el-radio-group>
          <div v-if="form.sendType === 'kefu'" class="hint">
            客服消息只能发给 48 小时内和公众号互动过的粉丝，超窗的会单独失败
          </div>
          <div v-else class="hint warn">
            模板消息需要先在微信后台申请模板，当前还没有可用模板，配好也发不出去
          </div>
        </el-form-item>
        <el-form-item label="收件人">
          <el-radio-group v-model="form.recipientType" class="stack-radio">
            <el-radio label="tag">按标签</el-radio>
            <el-radio label="openid">指定粉丝</el-radio>
          </el-radio-group>
          <el-select v-if="form.recipientType === 'tag'" v-model="form.recipientValue"
                     filterable allow-create placeholder="选择或输入标签名" class="grow">
            <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.name" />
          </el-select>
          <el-input v-else v-model="form.recipientValue" type="textarea" :rows="2"
                    placeholder="openid，多个用逗号或空格分隔" />
        </el-form-item>
        <el-form-item label="文案来源">
          <el-radio-group v-model="form.contentMode">
            <el-radio label="render">通道模板</el-radio>
            <el-radio label="passthrough">调用方传入</el-radio>
          </el-radio-group>
          <div v-if="form.contentMode === 'passthrough'" class="hint">
            请求体里必须带 <span class="mono">content</span> 字段，内容直接发出，不做占位符替换
          </div>
        </el-form-item>
        <el-form-item v-if="form.contentMode === 'render' && form.sendType === 'kefu'" label="文案">
          <el-input v-model="form.contentTemplate" type="textarea" :rows="6"
                    placeholder="支持 {变量名} 占位符" />
          <div class="hint">
            <span class="mono">{变量}</span> 取请求体同名字段，取不到就留空；
            <span class="mono">{变量?}</span> 取不到值时整行丢掉，适合「失败步骤：{failed_step?}」这种可有可无的行
          </div>
          <div class="preview-bar">
            <el-button link type="primary" @click="preview">预览渲染结果</el-button>
            <span v-if="previewVarsHint" class="muted">{{ previewVarsHint }}</span>
          </div>
          <pre v-if="previewText !== null" class="preview">{{ previewText || '(渲染结果为空)' }}</pre>
        </el-form-item>
        <template v-if="form.sendType === 'template'">
          <el-form-item label="模板 ID">
            <el-input v-model="form.templateId" placeholder="微信后台申请通过后的模板 ID" />
          </el-form-item>
          <el-form-item label="跳转链接">
            <el-input v-model="form.templateUrl" placeholder="选填，点击模板消息后跳转的地址" />
          </el-form-item>
          <el-form-item label="字段映射">
            <el-input v-model="templateDataText" type="textarea" :rows="5"
                      placeholder='[{"name":"first","value":"{title}"},{"name":"keyword1","value":"{branch}"}]' />
            <div class="hint">JSON 数组，value 里同样支持 <span class="mono">{变量}</span> 占位符</div>
          </el-form-item>
        </template>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="选填，写清楚谁在调用这个通道" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="hint inline">停用后网关会返回 skipped，手动也发不了</span>
        </el-form-item>
        <el-form-item label="允许接口调用">
          <el-switch v-model="form.gatewayEnabled" />
          <div class="hint">
            外部系统带上公众号的推送密钥（在「编辑公众号配置」里生成）才能调用网关。
            同一个公众号的通道共用这个密钥，只有打开这里的通道才会被触发；不影响在页面上手动发送
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="sendDialog" :title="`手动发送 - ${sendTarget.name || ''}`" width="620">
      <el-alert v-if="sendTarget.recipientType === 'tag'" type="warning" :closable="false" show-icon
                :title="`会真的发给标签「${sendTarget.recipientValue}」下的所有粉丝`" class="mb" />
      <el-form label-width="96">
        <el-form-item v-if="sendTarget.contentMode === 'passthrough'" label="内容">
          <el-input v-model="sendContent" type="textarea" :rows="6" placeholder="直接发出的文案" />
        </el-form-item>
        <el-form-item v-else label="变量">
          <el-input v-model="sendVarsText" type="textarea" :rows="6" placeholder='{"title":"标题","body":"正文"}' />
          <div class="hint">JSON 对象，键名对应文案里的占位符</div>
        </el-form-item>
      </el-form>
      <pre v-if="sendResult" class="preview">{{ sendResult }}</pre>
      <template #footer>
        <el-button @click="sendDialog = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="doSend">确认发送</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logDialog" title="推送记录" width="860">
      <el-table :data="logs" v-loading="logLoading" size="small">
        <el-table-column prop="createTime" label="时间" width="160" />
        <el-table-column prop="channelCode" label="通道" width="130" />
        <el-table-column label="来源" width="80">
          <template #default="{ row }">{{ row.source === 'manual' ? '手动' : '网关' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="logTagType(row)">
              {{ row.successCount }}/{{ row.recipientCount }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="发送内容" min-width="200" show-overflow-tooltip />
        <el-table-column prop="errorMsg" label="错误" min-width="180" show-overflow-tooltip />
      </el-table>
      <div class="pager">
        <el-pagination layout="prev,pager,next" :total="logTotal" :page-size="logParams.limit"
                       :current-page="logParams.page" @current-change="(p) => { logParams.page = p; loadLogs() }" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { api } from '@/api/console'

const list = ref([])
const total = ref(0)
const loading = ref(false)
const params = reactive({ page: 1, limit: 10 })
const tags = ref([])
const origin = location.origin

const dialog = ref(false)
const saving = ref(false)
const form = reactive({})
const templateDataText = ref('')
const previewText = ref(null)
const previewVarsHint = ref('')

const sendDialog = ref(false)
const sending = ref(false)
const sendTarget = reactive({})
const sendVarsText = ref('')
const sendContent = ref('')
const sendResult = ref('')

const logDialog = ref(false)
const logLoading = ref(false)
const logs = ref([])
const logTotal = ref(0)
const logParams = reactive({ page: 1, limit: 10, channelCode: '' })

async function load() {
  loading.value = true
  try {
    const r = await api.notifyChannels(params)
    list.value = r.page.list
    total.value = r.page.totalCount
  } finally {
    loading.value = false
  }
}

/** 标签列表只用来给「按标签」那个下拉做候选，取不到就退化成手填 */
async function loadTags() {
  try {
    tags.value = (await api.tags()).list || []
  } catch {
    tags.value = []
  }
}

function edit(row) {
  Object.assign(form, {
    id: null,
    name: '',
    code: '',
    sendType: 'kefu',
    recipientType: 'tag',
    recipientValue: '',
    contentMode: 'render',
    contentTemplate: '',
    templateId: '',
    templateUrl: '',
    remark: '',
    enabled: true,
    gatewayEnabled: false,
  }, row)
  templateDataText.value = form.templateData ? JSON.stringify(form.templateData, null, 2) : ''
  previewText.value = null
  previewVarsHint.value = ''
  dialog.value = true
}

async function save() {
  if (form.sendType === 'template') {
    // 字段映射存进去之后要给微信 SDK 用，格式错了得在这儿挡住，不然要到发送时才炸
    try {
      form.templateData = templateDataText.value ? JSON.parse(templateDataText.value) : null
    } catch {
      ElMessage.error('字段映射不是合法的 JSON')
      return
    }
    if (form.templateData && !Array.isArray(form.templateData)) {
      ElMessage.error('字段映射要是一个 JSON 数组')
      return
    }
  }
  saving.value = true
  try {
    await (form.id ? api.updateNotifyChannel(form) : api.saveNotifyChannel(form))
    dialog.value = false
    ElMessage.success('已保存')
    load()
  } finally {
    saving.value = false
  }
}

/**
 * 预览不落日志也不发消息
 *
 * 变量留空时后端会拿通道最近一次收到的 payload 来填，运营不用自己猜调用方传了什么。
 */
async function preview() {
  const r = await api.previewNotify({ id: form.id, contentTemplate: form.contentTemplate })
  previewText.value = r.content
  const keys = Object.keys(r.vars || {})
  previewVarsHint.value = keys.length ? `用最近一次收到的变量：${keys.join('、')}` : '没有可用变量，占位符都会渲染成空'
}

async function moreAction(command, row) {
  if (command === 'curl') {
    copyCurl(row)
  } else if (command === 'delete') {
    await ElMessageBox.confirm(`删除通道「${row.name}」后，调用这个地址的机器会推送失败。`, '删除通道', { type: 'warning' })
    await api.deleteNotifyChannels([row.id])
    ElMessage.success('已删除')
    load()
  }
}

/**
 * 给调用方直接抄的命令，省得对着文档拼请求头
 *
 * 密钥用占位符：推送密钥在公众号配置里管，这个页面不去取它
 */
function copyCurl(row) {
  if (!row.gatewayEnabled) {
    ElMessage.warning('这个通道还没允许接口调用，先在编辑里打开')
    return
  }
  const body = row.contentMode === 'passthrough'
    ? '{"content":"要发送的文案"}'
    : JSON.stringify(row.lastPayload || { title: '标题', body: '正文' })
  const cmd = [
    `curl -X POST ${origin}/wx/notify/${row.code} \\`,
    `  -H 'X-Notify-Secret: <公众号推送密钥>' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -d '${body}'`,
  ].join('\n')
  copy(cmd, 'curl 示例已复制')
}

async function copy(text, tip) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(tip)
  } catch {
    // 非 HTTPS 下 clipboard 不可用，退化成让用户自己选
    ElMessageBox.alert(text, '请手动复制', { customClass: 'copy-fallback' })
  }
}

function openSend(row) {
  Object.assign(sendTarget, row)
  sendVarsText.value = row.lastPayload ? JSON.stringify(row.lastPayload, null, 2) : '{}'
  sendContent.value = ''
  sendResult.value = ''
  sendDialog.value = true
}

async function doSend() {
  let vars
  if (sendTarget.contentMode === 'passthrough') {
    if (!sendContent.value.trim()) {
      ElMessage.error('内容不能为空')
      return
    }
    vars = { content: sendContent.value }
  } else {
    try {
      vars = sendVarsText.value.trim() ? JSON.parse(sendVarsText.value) : {}
    } catch {
      ElMessage.error('变量不是合法的 JSON')
      return
    }
  }
  sending.value = true
  try {
    const r = await api.sendNotify({ id: sendTarget.id, vars })
    // 一个人失败不代表整批失败，所以结果照实摊开，不用一句「成功」盖住
    sendResult.value = [
      `收件人 ${r.recipientCount} 人，成功 ${r.successCount}，失败 ${r.failCount}`,
      r.content ? `\n发送内容：\n${r.content}` : '',
      r.errorMsg ? `\n错误：${r.errorMsg}` : '',
    ].join('')
    if (r.failCount > 0) ElMessage.warning('部分或全部收件人发送失败')
    else if (r.successCount > 0) ElMessage.success('已发送')
  } finally {
    sending.value = false
  }
}

function openLogs(row) {
  logParams.channelCode = row ? row.code : ''
  logParams.page = 1
  logDialog.value = true
  loadLogs()
}

async function loadLogs() {
  logLoading.value = true
  try {
    const r = await api.notifyLogs(logParams)
    logs.value = r.page.list
    logTotal.value = r.page.totalCount
  } finally {
    logLoading.value = false
  }
}

function logTagType(row) {
  if (row.recipientCount === 0) return 'info'
  if (row.failCount === 0) return 'success'
  return row.successCount > 0 ? 'warning' : 'danger'
}

onMounted(() => {
  load()
  loadTags()
})
</script>

<style scoped>
.head-actions { display: flex; gap: 10px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.muted { color: #97a1b4; font-size: 12px; margin-right: 4px; }
.hint { color: #97a1b4; font-size: 12px; line-height: 1.6; margin-top: 4px; width: 100%; }
.hint.warn { color: #d08700; }
.hint.inline { margin-top: 0; margin-left: 10px; width: auto; }
.stack-radio { margin-bottom: 8px; width: 100%; }
.grow { width: 100%; }
.preview-bar { display: flex; align-items: center; gap: 10px; margin-top: 4px; width: 100%; }
.preview { margin: 8px 0 0; padding: 10px 12px; background: #f5f7fb; border: 1px solid #e8ecf3; border-radius: 6px;
  font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; width: 100%; }
.mb { margin-bottom: 16px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
