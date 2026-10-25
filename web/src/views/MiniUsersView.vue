<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">小程序用户</h1>
        <div class="page-subtitle">
          门户小程序的用户档案与应用可见范围。深色标签是登录后直达的默认应用。
          这张表按小程序 appid 归档，和顶部选中的公众号无关，切换公众号不会改变这里的数据
        </div>
      </div>
      <div class="head-actions">
        <el-button :disabled="!selected.length" @click="removeRows(selected)">
          删除（{{ selected.length }}）
        </el-button>
        <el-button type="primary" @click="edit({})">新增用户</el-button>
      </div>
    </div>

    <div class="surface table-surface">
      <div class="toolbar filter-bar">
        <el-input v-model="params.nickname" placeholder="昵称" clearable style="width: 160px" @change="reload" />
        <el-input v-model="params.userPhone" placeholder="手机号" clearable style="width: 160px" @change="reload" />
        <el-select v-model="params.miniAppid" placeholder="小程序 appid" clearable filterable
                   style="width: 220px" @change="reload">
          <el-option v-for="a in appidOptions" :key="a" :label="a" :value="a" />
        </el-select>
        <el-select v-model="params.defaultCode" placeholder="默认应用" clearable filterable
                   style="width: 150px" @change="reload">
          <el-option v-for="c in appCodeOptions" :key="c" :label="c" :value="c" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table :data="list" v-loading="loading" @selection-change="(rows) => selected = rows">
        <el-table-column type="selection" width="48" />
        <el-table-column label="昵称" min-width="120">
          <template #default="{ row }">
            <span v-if="row.nickname">{{ row.nickname }}</span>
            <span v-else class="muted">未填写</span>
          </template>
        </el-table-column>
        <el-table-column label="手机号" width="124">
          <template #default="{ row }">
            <span v-if="row.userPhone">{{ row.userPhone }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="小程序身份" min-width="210" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="mono">{{ row.miniOpenid }}</div>
            <div class="mono muted">{{ row.miniAppid }}</div>
          </template>
        </el-table-column>
        <el-table-column label="服务号关联" width="110">
          <template #default="{ row }">
            <el-tooltip v-if="row.serviceOpenid" placement="top"
                        :content="`${row.serviceAppid || '未知 appid'} / ${row.serviceOpenid}`">
              <el-tag size="small" type="success">已关联</el-tag>
            </el-tooltip>
            <el-tag v-else size="small" type="info">未关联</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="可见应用" min-width="190">
          <template #default="{ row }">
            <el-tag v-for="c in row.appCodes || []" :key="c" size="small" class="code-tag"
                    :effect="c === row.defaultCode ? 'dark' : 'light'">{{ c }}</el-tag>
            <span v-if="!(row.appCodes || []).length" class="muted">无</span>
            <!--
              后台的 /update 不校验 default_code 是否在 app_codes 里，所以库里可能存在
              「默认应用不在可见范围内」的档案。小程序端 toView 会把它当没设置处理，
              静默降级。这里显式标出来，否则运营永远不知道自己设的直达没生效
            -->
            <el-tag v-if="row.defaultCode && !(row.appCodes || []).includes(row.defaultCode)"
                    size="small" type="danger" class="code-tag">
              默认 {{ row.defaultCode }} 不在范围内
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="资料" width="88">
          <template #default="{ row }">
            <el-tag size="small" :type="row.profileFilled ? 'success' : 'warning'">
              {{ row.profileFilled ? '已完善' : '待完善' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.remark">{{ row.remark }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="建档时间" width="140" />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button v-if="!row.serviceOpenid" link type="primary" :loading="linkingId === row.id"
                       @click="link(row)">关联</el-button>
            <el-button link type="primary" @click="removeRows([row])">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination layout="total,prev,pager,next" :total="total" :page-size="params.limit"
                       :current-page="params.page" @current-change="(p) => { params.page = p; load() }" />
      </div>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑小程序用户' : '新增小程序用户'" width="640">
      <el-form label-width="104">
        <el-form-item label="小程序 appid">
          <el-input v-model="form.miniAppid" :disabled="!!form.id" placeholder="小程序的 appid" />
        </el-form-item>
        <el-form-item label="小程序 openid">
          <el-input v-model="form.miniOpenid" :disabled="!!form.id" placeholder="用户在该小程序下的 openid" />
          <div v-if="form.id" class="hint">
            appid + openid 是这条档案的业务唯一键，建好之后不能改，否则会和用户真实身份对不上
          </div>
          <div v-else class="hint">
            正常情况下档案由小程序登录时自动建立。这里手工建档适用于先把权限配好、用户还没登录过的场景
          </div>
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="选填" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.userPhone" placeholder="选填，11 位数字" maxlength="11" clearable />
          <div class="hint">
            手机号是关联服务号用户的退路依据（unionid 取不到时用）。库里是 char(11)，所以只收 11 位数字
          </div>
        </el-form-item>
        <el-form-item label="可见应用">
          <el-select v-model="form.appCodes" multiple filterable allow-create default-first-option
                     placeholder="输入应用 code 后回车即可新增" class="grow">
            <el-option v-for="c in appCodeOptions" :key="c" :label="c" :value="c" />
          </el-select>
          <div class="hint">留空表示工作台不给这个用户显示任何应用</div>
        </el-form-item>
        <el-form-item label="默认应用">
          <el-select v-model="form.defaultCode" clearable placeholder="选填，登录后直达该应用" class="grow">
            <el-option v-for="c in form.appCodes || []" :key="c" :label="c" :value="c" />
          </el-select>
          <div class="hint">候选项只来自上面的可见应用。清空表示登录后停在工作台</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="选填，仅后台可见，不下发到小程序" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="资料已完善">
          <el-switch v-model="form.profileFilled" />
          <span class="hint inline">关掉之后用户下次进小程序会再弹一次资料表单</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <div class="hint">
            停用不会让用户登录失败，登录接口照样返回档案，但可见应用会被当成空的，工作台里什么都看不到
          </div>
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
const linkingId = ref(null)
const params = reactive({ page: 1, limit: 10, nickname: '', userPhone: '', miniAppid: '', defaultCode: '' })

const dialog = ref(false)
const saving = ref(false)
const form = reactive({})

/**
 * 筛选下拉的候选值从当前页数据里收集。
 *
 * 后端没有「列出所有 appid / 应用 code」的接口，为一个筛选框加一个接口不值当。
 * 代价是候选项只覆盖当前页，所以两个下拉都开了 filterable，必要时能手输。
 */
const appidOptions = computed(() => uniq(list.value.map(r => r.miniAppid)))
const appCodeOptions = computed(() => uniq(list.value.flatMap(r => r.appCodes || [])))

function uniq(arr) {
  return [...new Set(arr.filter(Boolean))].sort()
}

async function load() {
  loading.value = true
  try {
    const r = await api.miniUsers(params)
    list.value = r.page.list
    total.value = r.page.totalCount
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
    id: null,
    // 新增时拿当前页里出现过的 appid 当默认值，省得每次手抄一串 wx 开头的 id
    miniAppid: appidOptions.value[0] || '',
    miniOpenid: '',
    nickname: '',
    userPhone: '',
    remark: '',
    defaultCode: '',
    profileFilled: false,
    enabled: true,
  }, row)
  // appCodes 来自后端的 JSON 列，可能是 null，而 el-select multiple 必须拿到数组。
  // 另外要拷一份，不然在对话框里勾选会直接改掉表格那一行的数据，取消也回不去
  form.appCodes = [...(row.appCodes || [])]
  dialog.value = true
}

async function save() {
  if (!form.id && (!(form.miniAppid || '').trim() || !(form.miniOpenid || '').trim())) {
    ElMessage.error('小程序 appid 与 openid 不能为空')
    return
  }
  const phone = (form.userPhone || '').trim()
  if (phone && !/^\d{11}$/.test(phone)) {
    ElMessage.error('手机号要是 11 位数字')
    return
  }
  // 后端 /update 不做这个校验，设歪了小程序端会静默忽略直达，所以在这儿挡住
  if (form.defaultCode && !form.appCodes.includes(form.defaultCode)) {
    ElMessage.error('默认应用必须在可见应用范围内')
    return
  }

  /*
   * 只送这个页面真正负责的字段，不要把整行回传。
   *
   * 一是 unionid / serviceOpenid 是后端自己算出来的，回传等于让页面有机会覆盖它们；
   * 二是 createTime / updateTime 由数据库默认值维护（实体上是 insert/update NEVER），
   * 回传没用还要多走一次日期反序列化。
   *
   * 清空字段必须送空串，不能送 null：MyBatis-Plus 默认字段策略是 NOT_NULL，
   * updateById 会跳过 null 字段，送 null 等于「这个字段别动」，备注和默认应用就永远清不掉。
   * 空串会真的写进库，而小程序端 toView 对 default_code 用的是 hasText 判断，
   * 空串和 NULL 一样被当成「没设默认应用」。
   */
  const payload = {
    id: form.id || undefined,
    miniAppid: (form.miniAppid || '').trim(),
    miniOpenid: (form.miniOpenid || '').trim(),
    nickname: form.nickname ?? '',
    userPhone: phone,
    remark: form.remark ?? '',
    defaultCode: form.defaultCode ?? '',
    appCodes: form.appCodes,
    profileFilled: form.profileFilled,
    enabled: form.enabled,
  }

  saving.value = true
  try {
    await (form.id ? api.updateMiniUser(payload) : api.saveMiniUser(payload))
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

/**
 * 手动触发一次服务号关联。
 *
 * 后端按 unionid 优先、手机号兜底去找对应的公众号粉丝，手机号命中多条时宁可不关联，
 * 所以补完手机号之后点一次才有意义。找不到匹配时接口回错误，http 拦截器会弹提示
 */
async function link(row) {
  linkingId.value = row.id
  try {
    await api.linkMiniUser(row.id)
    ElMessage.success('已关联服务号用户')
    load()
  } catch {
    /* 失败原因已由 http 拦截器提示 */
  } finally {
    linkingId.value = null
  }
}

async function removeRows(rows) {
  if (!rows.length) return
  const who = rows.length === 1
    ? `「${rows[0].nickname || rows[0].miniOpenid}」`
    : `这 ${rows.length} 个用户`
  try {
    await ElMessageBox.confirm(
      `删除${who}的档案后，用户下次登录小程序会重新建档，可见应用和默认应用都要重新配。`,
      '删除小程序用户',
      { type: 'warning' },
    )
  } catch {
    // 点了取消，ElMessageBox 是 reject 出来的，这里必须吞掉
    return
  }
  try {
    await api.deleteMiniUsers(rows.map(r => r.id))
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
.code-tag { margin-right: 4px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
