<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">自定义菜单</h1>
        <div class="page-subtitle">编辑并同步当前公众号底部菜单</div>
      </div>
      <el-button type="primary" :loading="saving" @click="save">保存并发布</el-button>
    </div>
    <div class="editor">
      <div class="phone surface">
        <div class="phone-title">{{ account?.name }}</div>
        <div class="phone-body">菜单预览</div>
        <div class="phone-nav">
          <span v-for="item in menu.buttons || []" :key="item.name">{{ item.name }}</span>
        </div>
      </div>
      <div class="surface form">
        <el-empty v-if="!menu.buttons?.length" description="暂无菜单">
          <el-button type="primary" @click="add">添加菜单</el-button>
        </el-empty>
        <template v-else>
          <div v-for="(item, index) in menu.buttons" :key="index" class="menu-row">
            <div class="menu-main">
              <el-input v-model="item.name" maxlength="16" show-word-limit placeholder="菜单名称" />
              <el-select v-model="item.type" @change="onTypeChange(item)">
                <el-option label="点击事件" value="click" />
                <el-option label="网页链接" value="view" />
                <el-option label="小程序" value="miniprogram" />
              </el-select>
              <el-button text type="danger" @click="menu.buttons.splice(index, 1)">删除</el-button>
            </div>
            <div v-if="item.type === 'view'" class="menu-extra">
              <el-input v-model="item.url" placeholder="https://" />
            </div>
            <div v-else-if="item.type === 'miniprogram'" class="menu-extra">
              <el-input v-model="item.appId" placeholder="小程序 appid" />
              <el-input v-model="item.pagePath" placeholder="页面路径，如 pages/launch/launch" />
              <el-input v-model="item.url" placeholder="兜底 url（老版本微信打开）" />
            </div>
            <div v-else class="menu-extra">
              <el-input v-model="item.key" placeholder="事件 KEY" />
            </div>
          </div>
          <el-button v-if="menu.buttons.length < 3" @click="add">添加一级菜单</el-button>
        </template>
      </div>
    </div>
  </div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/console'
import { session } from '@/lib/session'

const account = computed(() => session.account)
const saving = ref(false)
const menu = reactive({ buttons: [] })
// 该公众号已关联的门户小程序 appid，新增小程序菜单时带出来，避免手填错
const miniAppid = ref('')

onMounted(async () => {
  const r = await api.menu()
  menu.buttons = r.data?.menu?.buttons || r.data?.buttons || []
  try {
    const cfg = await api.accountConfig(account.value.appid)
    miniAppid.value = cfg.data?.account?.miniAppid || ''
  } catch (e) {
    // 没配小程序或没有配置查看权限时不影响菜单编辑
  }
})

function add() {
  menu.buttons.push({ name: '新菜单', type: 'click', key: '', url: '', appId: '', pagePath: '' })
}

function onTypeChange(item) {
  if (item.type === 'miniprogram' && !item.appId) {
    item.appId = miniAppid.value
  }
}

// 小程序按钮的字段名必须用 appId / pagePath（驼峰）：
// 后端用 Jackson 反序列化 WxMenu，不认 wxjava 上的 Gson @SerializedName，
// 写成 appid / pagepath 会被当未知字段静默丢弃。
function validate() {
  for (const item of menu.buttons) {
    if (!item.name?.trim()) return '请填写菜单名称'
    if (item.type === 'miniprogram') {
      if (!item.appId?.trim()) return `「${item.name}」缺少小程序 appid`
      if (!item.pagePath?.trim()) return `「${item.name}」缺少页面路径`
      if (!item.url?.trim()) return `「${item.name}」缺少兜底 url`
    }
  }
  return ''
}

async function save() {
  const error = validate()
  if (error) {
    ElMessage.error(error)
    return
  }
  saving.value = true
  try {
    await api.saveMenu(menu)
    ElMessage.success('菜单已同步至微信')
  } finally {
    saving.value = false
  }
}
</script>
<style scoped>
.editor {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: 24px;
}
.phone {
  height: 600px;
  overflow: hidden;
  background: #f7f8fa;
}
.phone-title {
  height: 54px;
  background: #273248;
  color: #fff;
  padding: 17px;
  text-align: center;
}
.phone-body {
  height: 490px;
  padding: 20px;
  color: #97a2b5;
}
.phone-nav {
  height: 56px;
  background: #fff;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
}
.phone-nav span {
  display: grid;
  place-items: center;
  border-left: 1px solid #edf0f4;
  font-size: 13px;
}
.form {
  padding: 20px;
}
.menu-row {
  padding: 12px;
  border: 1px solid #edf0f4;
  border-radius: 8px;
  margin-bottom: 12px;
}
.menu-main {
  display: grid;
  grid-template-columns: 150px 130px 1fr;
  gap: 10px;
}
.menu-extra {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}
</style>
