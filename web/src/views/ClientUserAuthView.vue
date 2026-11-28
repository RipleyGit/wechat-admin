<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">用户应用管理</h1>
        <div class="page-subtitle">
          谁能用哪个子应用。一行一条授权记录（用户 × 应用），深色标签是登录后直达的默认应用。
          这张表按用户 openid 归档，和顶部选中的公众号无关，切换公众号不会改变这里的数据
        </div>
      </div>
      <div class="head-actions">
        <el-button :disabled="!selected.length" @click="removeRows(selected)">
          删除（{{ selected.length }}）
        </el-button>
        <el-button type="primary" @click="edit({})">新增授权</el-button>
      </div>
    </div>

    <div class="surface table-surface">
      <div class="toolbar filter-bar">
        <el-input v-model="params.userId" placeholder="用户 openid" clearable style="width: 240px" @change="reload" />
        <el-select v-model="params.clientCode" placeholder="应用" clearable filterable
                   style="width: 160px" @change="reload">
          <el-option v-for="c in appCodeOptions" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="params.status" placeholder="状态" clearable style="width: 120px" @change="reload">
          <el-option label="正常" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table :data="list" v-loading="loading" @selection-change="(rows) => selected = rows">
        <el-table-column type="selection" width="48" />
        <el-table-column label="用户 openid" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="mono">{{ row.userId }}</div>
            <div class="mono muted">{{ row.userType }}</div>
          </template>
        </el-table-column>
        <el-table-column label="应用" min-width="170">
          <template #default="{ row }">
            <el-tag size="small" :effect="row.isDefault ? 'dark' : 'light'">{{ row.clientCode }}</el-tag>
            <span v-if="row.clientName" class="muted"> {{ row.clientName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="72">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" size="small" type="success">默认</el-tag>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" width="150">
          <template #default="{ row }">
            <span v-if="row.lastLoginAt">{{ row.lastLoginAt }}</span>
            <span v-else class="muted">从未</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="150" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button link type="primary" @click="removeRows([row])">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination layout="total,prev,pager,next" :total="total" :page-size="params.limit"
                       :current-page="params.page" @current-change="(p) => { params.page = p; load() }" />
      </div>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑授权' : '新增授权'" width="560">
      <el-form label-width="104">
        <el-form-item label="用户 openid">
          <el-input v-model="form.userId" :disabled="!!form.id" placeholder="用户在门户小程序下的 openid" />
          <div v-if="form.id" class="hint">
            用户 + 应用是这条记录的业务唯一键，建好之后不能改
          </div>
          <div v-else class="hint">
            授权的是小程序用户在门户下的 openid（已关联服务号粉丝的那个 openid）
          </div>
        </el-form-item>
        <el-form-item label="应用">
          <el-select v-model="form.clientCode" :disabled="!!form.id" filterable allow-create
                     default-first-option placeholder="输入应用 code 后回车即可新增" class="grow">
            <el-option v-for="c in appCodeOptions" :key="c" :label="c" :value="c" />
          </el-select>
          <div class="hint">和门户工作台的应用 code 一致，如 app_pco / app_cloude / app_aptum</div>
        </el-form-item>
        <el-form-item label="应用名">
          <el-input v-model="form.clientName" placeholder="选填，仅展示用" maxlength="100" clearable />
        </el-form-item>
        <el-form-item label="默认应用">
          <el-switch v-model="form.isDefault" />
          <span class="hint inline">开启后登录直达该应用；同一用户至多一个默认，开这条会清掉他其他的默认</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.statusOn" active-text="正常" inactive-text="禁用" />
          <div class="hint">禁用后这个应用不会出现在用户的工作台里</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/console'

const list = ref([])
const total = ref(0)
const loading = ref(false)
const selected = ref([])
const params = reactive({ page: 1, limit: 10, userId: '', clientCode: '', status: '' })

const dialog = ref(false)
const saving = ref(false)
const form = reactive({})

// 筛选下拉的候选值从当前页数据里收集，后端没有「列出所有应用 code」的接口。
// 代价是候选项只覆盖当前页，所以下拉开了 filterable，必要时能手输
const appCodeOptions = computed(() => uniq(list.value.map(r => r.clientCode)))

function uniq(arr) {
  return [...new Set(arr.filter(Boolean))].sort()
}

async function load() {
  loading.value = true
  try {
    const r = await api.clientUserAuths(params)
    list.value = r.data.list
    total.value = r.data.totalCount
  } finally {
    loading.value = false
  }
}

/** 改筛选条件要回到第一页，否则在第 3 页改条件可能直接落到空结果上 */
function reload() {
  params.page = 1
  load()
}

function edit(row) {
  Object.assign(form, {
    id: row.id || null,
    userId: row.userId || '',
    clientCode: row.clientCode || '',
    clientName: row.clientName || '',
    isDefault: !!row.isDefault,
    // 后端 status 是 0/1，界面用布尔开关，保存时再转回去
    statusOn: row.status === undefined ? true : row.status === 1,
  })
  dialog.value = true
}

async function save() {
  if (!(form.userId || '').trim() || !(form.clientCode || '').trim()) {
    ElMessage.error('用户 openid 与应用不能为空')
    return
  }

  // 只送页面负责的字段。create_time / updated_at 由数据库维护，
  // auth_identifier / credential 本期不用，后端也会强制清空，这里不回传
  const payload = {
    id: form.id || undefined,
    userId: (form.userId || '').trim(),
    clientCode: (form.clientCode || '').trim(),
    clientName: (form.clientName || '').trim(),
    isDefault: form.isDefault,
    status: form.statusOn ? 1 : 0,
  }

  saving.value = true
  try {
    await (form.id ? api.updateClientUserAuth(payload) : api.saveClientUserAuth(payload))
    dialog.value = false
    ElMessage.success('已保存')
    load()
  } catch {
    // 接口错误 http 拦截器已经弹过提示了，这里只是别让 reject 变成 unhandled，
    // 同时保留对话框不关，用户能接着改
  } finally {
    saving.value = false
  }
}

async function removeRows(rows) {
  if (!rows.length) return
  const who = rows.length === 1
    ? `「${rows[0].userId} / ${rows[0].clientCode}」`
    : `这 ${rows.length} 条授权`
  try {
    await ElMessageBox.confirm(
      `删除${who}后，用户工作台里就看不到对应的应用了。`,
      '删除授权',
      { type: 'warning' },
    )
  } catch {
    // 点了取消，ElMessageBox 是 reject 出来的，这里必须吞掉
    return
  }
  try {
    await api.deleteClientUserAuths(rows.map(r => r.id))
    ElMessage.success('已删除')
    load()
  } catch {
    /* 接口错误已由 http 拦截器提示 */
  }
}

onMounted(load)
</script>

<style scoped>
.head-actions { display: flex; gap: 10px; }
.filter-bar { margin-bottom: 16px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.muted { color: #97a1b4; font-size: 12px; }
.hint { color: #97a1b4; font-size: 12px; line-height: 1.6; margin-top: 4px; width: 100%; }
.hint.inline { margin-top: 0; margin-left: 10px; width: auto; }
.grow { width: 100%; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
