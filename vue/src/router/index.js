import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/ipr' },

  // 登录 / 注册
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue') },
  { path: '/register', name: 'Register', component: () => import('@/views/Register.vue') },

  // 解析融合工作台（图一界面）—— 独立全屏布局
  { path: '/ipr', name: 'IprInterface', component: () => import('@/views/IprInterface.vue') },

  // 前台布局
  {
    path: '/front',
    component: () => import('@/views/Front.vue'),
    redirect: '/front/home',
    children: [
      { path: 'home', name: 'FrontHome', component: () => import('@/views/front/Home.vue') },
      { path: 'input_file', name: 'FrontInput', component: () => import('@/views/front/InputFile.vue') },
      { path: 'person', name: 'FrontPerson', component: () => import('@/views/front/Person.vue') },
      { path: 'password', name: 'FrontPassword', component: () => import('@/views/front/Password.vue') }
    ]
  },

  // 404
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/404.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 简单登录守卫
const whiteList = ['/login', '/register', '/ipr']
router.beforeEach((to, from, next) => {
  const account = localStorage.getItem('account')
  if (account || whiteList.includes(to.path)) {
    next()
  } else {
    next('/login')
  }
})

export default router
