import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '@/composables/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'knowledge-bases',
          name: 'knowledge-bases',
          component: () => import('@/views/KnowledgeBaseView.vue'),
          meta: { title: '知识库' },
        },
        {
          path: 'documents',
          name: 'documents',
          component: () => import('@/views/DocumentsView.vue'),
          meta: { title: '文档' },
        },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('@/views/ChatView.vue'),
          meta: { title: 'AI 对话' },
        },
        {
          path: 'traces',
          name: 'traces',
          component: () => import('@/views/TraceView.vue'),
          meta: { title: '执行监控' },
        },
        {
          path: 'memories',
          name: 'memories',
          component: () => import('@/views/MemoryView.vue'),
          meta: { title: '长期记忆' },
        },
        {
          path: 'evaluation',
          name: 'evaluation',
          component: () => import('@/views/EvaluationView.vue'),
          meta: { title: '评测中心' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  const { state } = useSession()
  if (to.name !== 'login' && !state.ownerId) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && state.ownerId) {
    return { name: 'dashboard' }
  }
  return true
})

export default router
