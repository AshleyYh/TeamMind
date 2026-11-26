<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useSession } from '@/composables/session'

const router = useRouter()
const route = useRoute()
const { login } = useSession()
const loading = ref(false)
const form = reactive({
  ownerId: 1,
  displayName: '本地开发者',
})

function submit() {
  if (!Number.isInteger(form.ownerId) || form.ownerId <= 0) {
    ElMessage.warning('ownerId 必须是正整数')
    return
  }
  loading.value = true
  login(form.ownerId, form.displayName)
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
  router.replace(redirect)
}
</script>

<template>
  <div class="login-page">
    <section class="login-hero">
      <div>
        <div class="hero-kicker">TeamMind · Agentic RAG Platform</div>
        <h1>把研发文档变成可追溯的团队记忆。</h1>
        <p>
          从 PDF / DOCX 入库、混合检索、Reranker 重排，到 ReAct Agent 工具调用、短期会话、
          确认制长期记忆和全链路 Trace。每个答案都能回到真实 Chunk。
        </p>

        <div class="hero-points">
          <div class="hero-point">
            <strong>可追溯</strong>
            Evidence Board 保存原文、版本、页码和引用状态。
          </div>
          <div class="hero-point">
            <strong>可执行</strong>
            Function Calling + ReAct，多工具连续调用和失败降级。
          </div>
          <div class="hero-point">
            <strong>可记忆</strong>
            Redis 会话摘要 + 人工确认的长期记忆。
          </div>
          <div class="hero-point">
            <strong>可评测</strong>
            Recall@K、MRR、NDCG 与引用质量报告。
          </div>
        </div>
      </div>
      <div style="color: #6f8494; font-size: 12px">
        Phase 8 · 前端控制台 · 开发模式不包含真实登录认证
      </div>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <h2>进入控制台</h2>
        <div class="sub">
          当前后端尚未接入身份认证。这里使用开发期 ownerId 模拟归属，所有 API 仍以
          <span class="mono">ownerId</span> 做资源隔离。
        </div>

        <div class="dev-note">
          <strong>开发模式提示：</strong>
          ownerId 不是安全认证。请只在本地或受控环境使用，不要把这个入口部署到公网。
        </div>

        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="ownerId">
            <el-input-number v-model="form.ownerId" :min="1" :max="999999" controls-position="right" />
          </el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="form.displayName" placeholder="例如：本地开发者" @keyup.enter="submit" />
          </el-form-item>
          <el-button type="primary" size="large" :loading="loading" style="width: 100%" @click="submit">
            <el-icon style="margin-right: 6px"><Lock /></el-icon>
            进入 TeamMind
            <el-icon style="margin-left: 6px"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <p class="form-hint" style="margin-top: 18px">
          local profile 会初始化 ownerId=1 的占位用户；如果使用其他 ownerId，需要先在
          <span class="mono">sys_user</span> 中存在对应记录。
        </p>
      </div>
    </section>
  </div>
</template>
