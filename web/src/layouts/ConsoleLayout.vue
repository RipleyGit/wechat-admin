<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">W</span><span>微信运营台</span></div>
      <nav>
        <RouterLink v-for="item in nav" :key="item.to" :to="item.to" class="nav-item">
          <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
          <el-badge v-if="item.badge && unread > 0" :value="unread" :max="99" class="nav-badge" />
        </RouterLink>
      </nav>
    </aside>
    <main>
      <header class="topbar">
        <div class="account-pill"><span class="account-dot"></span>{{ account?.name || '未选择公众号' }}</div>
        <div class="top-actions">
          <el-button text @click="$router.push('/accounts')">切换公众号</el-button>
          <el-dropdown @command="handleCommand">
            <span class="user-menu"><el-icon><User /></el-icon>{{ user?.username || '管理员' }}<el-icon><ArrowDown /></el-icon></span>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ArrowDown, ChatDotRound, ChatLineRound, Grid, Picture, Promotion, PriceTag, Tickets, User, UserFilled } from '@element-plus/icons-vue'
import { api } from '@/api/console'
import { session } from '@/lib/session'

/** 未读轮询间隔。没有 WebSocket，只能轮询 */
const UNREAD_POLL_INTERVAL = 30000

const user = ref(null)
const unread = ref(0)
const account = computed(() => session.account)
let unreadTimer = null
const nav = [
  { label: '运营概览', to: '/', icon: Grid },
  { label: '粉丝管理', to: '/followers', icon: UserFilled },
  { label: '用户标签', to: '/tags', icon: PriceTag },
  { label: '素材中心', to: '/materials', icon: Picture },
  { label: '自定义菜单', to: '/menu', icon: Promotion },
  { label: '粉丝私信', to: '/messages', icon: ChatLineRound, badge: true },
  { label: '自动回复', to: '/replies', icon: ChatDotRound },
  { label: '渠道二维码', to: '/qrcodes', icon: Tickets },
]

/**
 * 红点放在 layout 而不是私信页里：运营在系统里干别的事时也得能看见。
 * 48h 窗口一过就回不了消息了。
 */
async function loadUnread() {
  // 后台标签页不发请求，否则多开几个就是每 30s 好几个请求
  if (document.hidden) return
  try {
    unread.value = (await api.msgUnreadCount()).count || 0
  } catch {
    // 轮询失败不打扰用户，下一轮自己会好
  }
}

onMounted(async () => {
  user.value = (await api.me()).user
  loadUnread()
  unreadTimer = setInterval(loadUnread, UNREAD_POLL_INTERVAL)
})
onUnmounted(() => clearInterval(unreadTimer))
async function handleCommand(command) {
  if (command === 'logout') {
    try { await api.logout() } finally { session.clear(); location.href = '/login' }
  }
}
</script>

<style scoped>
.shell { display: grid; grid-template-columns: 224px 1fr; min-height: 100vh; background: #f5f7fb; }
.sidebar { background: #101829; color: #c9d2e5; padding: 18px 12px; }
.brand { height: 52px; display: flex; align-items: center; gap: 10px; color: white; font-size: 17px; font-weight: 650; padding: 0 10px 18px; border-bottom: 1px solid #28344b; }
.brand-mark { display:grid; place-items:center; width:28px; height:28px; background:#16a36a; border-radius:6px; }
nav { padding-top: 18px; display: grid; gap: 4px; }
.nav-item { height: 42px; display:flex; align-items:center; gap:12px; padding:0 12px; border-radius:6px; font-size:14px; line-height:20px; }
.nav-icon { width:18px; height:18px; flex:0 0 18px; font-size:18px; }
.nav-item > span { min-width:0; white-space:nowrap; }
.nav-badge { margin-left:auto; }
.nav-badge :deep(.el-badge__content) { border:none; }
.nav-item:hover, .nav-item.router-link-exact-active { color:#fff; background:#243047; }
main { min-width: 0; }
.topbar { height: 68px; display:flex; justify-content:space-between; align-items:center; padding:0 28px; background:#fff; border-bottom:1px solid #e8ecf3; }
.account-pill { font-size: 14px; color:#3e4b63; display:flex; align-items:center; gap:8px; }
.account-dot { width:8px; height:8px; border-radius:50%; background:#17a36c; }
.top-actions, .user-menu { display:flex; align-items:center; gap:10px; }.user-menu { cursor:pointer; color:#506079; font-size:14px; }
</style>
