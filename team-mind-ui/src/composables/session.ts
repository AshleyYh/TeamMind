import { computed, reactive } from 'vue'

const OWNER_KEY = 'teammind.ownerId'
const NAME_KEY = 'teammind.displayName'

const storedOwner = Number(localStorage.getItem(OWNER_KEY) || '0')

const state = reactive({
  // 未登录时保持 0，让路由守卫进入开发模式登录页；登录后写入 localStorage。
  ownerId: Number.isFinite(storedOwner) && storedOwner > 0 ? storedOwner : 0,
  displayName: localStorage.getItem(NAME_KEY) || '本地开发者',
})

export function useSession() {
  const isLoggedIn = computed(() => state.ownerId > 0)

  function login(ownerId: number, displayName: string) {
    state.ownerId = ownerId
    state.displayName = displayName.trim() || '本地开发者'
    localStorage.setItem(OWNER_KEY, String(ownerId))
    localStorage.setItem(NAME_KEY, state.displayName)
  }

  function logout() {
    state.ownerId = 0
    localStorage.removeItem(OWNER_KEY)
  }

  return { state, isLoggedIn, login, logout }
}
