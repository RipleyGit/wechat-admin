import { createRouter, createWebHistory } from 'vue-router'
import { session } from '@/lib/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    {
      // 通道模板二维码，扫码匿名查看
      path: '/channel-info/:token',
      name: 'channelInfo',
      component: () => import('@/views/ChannelInfoView.vue'),
    },
    { path: '/accounts', name: 'accounts', component: () => import('@/views/AccountSelectView.vue') },
    {
      path: '/',
      component: () => import('@/layouts/ConsoleLayout.vue'),
      children: [
        { path: '', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
        { path: 'followers', name: 'followers', component: () => import('@/views/FollowersView.vue') },
        { path: 'tags', name: 'tags', component: () => import('@/views/TagsView.vue') },
        { path: 'materials', name: 'materials', component: () => import('@/views/MaterialsView.vue') },
        { path: 'menu', name: 'menu', component: () => import('@/views/MenuView.vue') },
        { path: 'messages', name: 'messages', component: () => import('@/views/MessagesView.vue') },
        { path: 'replies', name: 'replies', component: () => import('@/views/RepliesView.vue') },
        { path: 'qrcodes', name: 'qrcodes', component: () => import('@/views/QrcodesView.vue') },
        { path: 'notify', name: 'notify', component: () => import('@/views/NotifyView.vue') },
        { path: 'miniusers', name: 'miniusers', component: () => import('@/views/MiniUsersView.vue') },
      ],
    },
  ],
})

// 这些页面不依赖登录态：登录页本身，以及扫码打开的通道信息页
const PUBLIC_ROUTES = ['login', 'channelInfo']

router.beforeEach((to) => {
  if (PUBLIC_ROUTES.includes(to.name)) return
  if (!session.token) return { name: 'login' }
  if (to.name !== 'accounts' && !session.account) return { name: 'accounts' }
})

export default router
