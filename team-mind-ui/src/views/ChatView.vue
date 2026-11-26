<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  Delete,
  MagicStick,
  Promotion,
  Refresh,
  VideoPause,
  View,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import { api, streamAgentChat } from '@/api'
import type {
  AgentAnswer,
  AgentStep,
  Citation,
  ConversationItem,
  Id,
  KnowledgeBase,
  ModelUsage,
  StoredMessage,
  TaskUnderstanding,
} from '@/api/types'
import { useSession } from '@/composables/session'
import { formatTime, statusTagType } from '@/utils/format'

interface UiMessage extends StoredMessage {
  citations?: Citation[]
  traceId?: string | null
  termination?: string
  warnings?: string[]
  usage?: ModelUsage
  taskType?: string
}

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('css', css)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('python', python)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)

const route = useRoute()
const router = useRouter()
const { state } = useSession()

const conversations = ref<ConversationItem[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const selectedKbIds = ref<Id[]>([])
const conversationId = ref<string | null>(null)
const messages = ref<UiMessage[]>([])
const input = ref('')
const streaming = ref(false)
const loadingConversation = ref(false)
const task = ref<TaskUnderstanding | null>(null)
const steps = ref<AgentStep[]>([])
const currentTraceId = ref<string>('')
const usage = ref<ModelUsage | null>(null)
const termination = ref('')
const warnings = ref<string[]>([])
const activeContextTab = ref<'evidence' | 'execution'>('evidence')
const expandedCitation = ref<number | null>(null)
const scrollContainer = ref<HTMLElement | null>(null)
const memoryDialogVisible = ref(false)
const memoryCandidates = ref<Awaited<ReturnType<typeof api.extractMemories>>['memories']>([])
const memoryWarnings = ref<string[]>([])
const summaryText = ref('')
const extracting = ref(false)
let abortController: AbortController | null = null

const lastAssistant = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (messages.value[index].role === 'assistant') {
      return messages.value[index]
    }
  }
  return null
})
const citations = computed(() => lastAssistant.value?.citations || [])
const canSend = computed(() => input.value.trim().length > 0 && !streaming.value)
const currentTitle = computed(
  () => conversations.value.find((item) => item.conversationId === conversationId.value)?.title || '新会话',
)

function renderMarkdown(text: string): string {
  if (!text) {
    return ''
  }
  const html = marked.parse(text, { async: false }) as string
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}

function highlightCodeBlocks() {
  nextTick(() => {
    document.querySelectorAll('.chat-scroll .markdown-body pre code').forEach((element) => {
      if (!element.getAttribute('data-highlighted')) {
        hljs.highlightElement(element as HTMLElement)
      }
    })
  })
}

function scrollToBottom() {
  nextTick(() => {
    const element = scrollContainer.value
    if (element) {
      element.scrollTop = element.scrollHeight
    }
  })
}

async function loadConversations() {
  try {
    conversations.value = await api.listConversations(state.ownerId)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function loadKnowledgeBases() {
  try {
    knowledgeBases.value = await api.listKnowledgeBases(state.ownerId)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function newConversation() {
  if (streaming.value) {
    abortController?.abort()
  }
  conversationId.value = null
  messages.value = []
  task.value = null
  steps.value = []
  currentTraceId.value = ''
  usage.value = null
  termination.value = ''
  warnings.value = []
  summaryText.value = ''
  input.value = ''
}

async function selectConversation(item: ConversationItem) {
  if (streaming.value) {
    ElMessage.warning('请先停止当前生成')
    return
  }
  if (item.conversationId === conversationId.value) {
    return
  }
  loadingConversation.value = true
  try {
    const data = await api.conversationMessages(item.conversationId, state.ownerId, 50)
    conversationId.value = data.conversationId
    messages.value = data.messages.map((message) => ({ ...message }))
    task.value = null
    steps.value = []
    currentTraceId.value = ''
    usage.value = null
    termination.value = ''
    warnings.value = []
    summaryText.value = data.summary?.summary || ''
    scrollToBottom()
    highlightCodeBlocks()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loadingConversation.value = false
  }
}

async function deleteConversation(item: ConversationItem) {
  try {
    await ElMessageBox.confirm(`删除会话「${item.title || item.conversationId}」？`, '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch (_) {
    return
  }
  try {
    await api.deleteConversation(item.conversationId, state.ownerId)
    if (conversationId.value === item.conversationId) {
      newConversation()
    }
    await loadConversations()
    ElMessage.success('会话已删除')
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function createConversationId(): string {
  const random = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID().replace(/-/g, '')
    : Math.random().toString(36).slice(2)
  return `conv-${random.slice(0, 36)}`
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) {
    return
  }
  if (!conversationId.value) {
    conversationId.value = createConversationId()
  }
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  const assistant: UiMessage = {
    role: 'assistant',
    content: '',
    citations: [],
    warnings: [],
  }
  messages.value.push(assistant)
  task.value = null
  steps.value = []
  currentTraceId.value = ''
  usage.value = null
  termination.value = ''
  warnings.value = []
  summaryText.value = ''
  streaming.value = true
  abortController = new AbortController()
  scrollToBottom()

  try {
    await streamAgentChat(
      {
        message: text,
        knowledgeBaseIds: selectedKbIds.value,
        ownerId: state.ownerId,
        topK: 5,
        conversationId: conversationId.value,
        history: [],
      },
      {
        onTrace(traceId) {
          currentTraceId.value = traceId
          assistant.traceId = traceId
        },
        onTask(value) {
          task.value = value
          assistant.taskType = value.taskType
          if (value.warnings?.length) {
            warnings.value.push(...value.warnings)
          }
        },
        onStep(step) {
          steps.value.push(step)
          scrollToBottom()
        },
        onDelta(delta) {
          assistant.content += delta
          scrollToBottom()
        },
        onDone(answer: AgentAnswer) {
          assistant.content = answer.answer
          assistant.citations = answer.citations
          assistant.traceId = answer.traceId
          assistant.termination = answer.termination
          assistant.warnings = answer.warnings
          assistant.usage = answer.usage
          assistant.taskType = answer.taskType
          task.value = answer.taskUnderstanding
          steps.value = answer.steps
          currentTraceId.value = answer.traceId
          usage.value = answer.usage
          termination.value = answer.termination
          warnings.value = answer.warnings
          if (answer.conversationId) {
            conversationId.value = answer.conversationId
          }
          highlightCodeBlocks()
          scrollToBottom()
        },
        onError(error) {
          assistant.content = assistant.content || `执行失败：${error.message}`
          assistant.warnings = [error.message]
          warnings.value = [error.message]
          ElMessage.error(error.message)
        },
      },
      abortController.signal,
    )
  } catch (error) {
    const name = (error as Error).name
    if (name === 'AbortError') {
      assistant.content = assistant.content || '（已停止生成）'
    } else {
      const message = (error as Error).message || '请求失败'
      assistant.content = assistant.content || `请求失败：${message}`
      assistant.warnings = [message]
      warnings.value = [message]
      ElMessage.error(message)
    }
  } finally {
    streaming.value = false
    abortController = null
    await loadConversations()
    highlightCodeBlocks()
  }
}

function stop() {
  abortController?.abort()
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    send()
  }
}

function usePrompt(prompt: string) {
  input.value = prompt
}

function selectCitation(index: number) {
  activeContextTab.value = 'evidence'
  expandedCitation.value = expandedCitation.value === index ? null : index
}

async function extractMemories() {
  if (!conversationId.value) {
    ElMessage.warning('请先完成至少一轮对话')
    return
  }
  extracting.value = true
  try {
    const result = await api.extractMemories(conversationId.value, state.ownerId)
    memoryCandidates.value = result.memories
    memoryWarnings.value = result.warnings
    memoryDialogVisible.value = true
    if (!result.memories.length) {
      ElMessage.info('本次会话没有提取到可长期保存的记忆候选')
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    extracting.value = false
  }
}

async function confirmCandidate(index: number) {
  const candidate = memoryCandidates.value[index]
  if (!candidate) {
    return
  }
  try {
    await api.confirmMemory(candidate.id, state.ownerId)
    ElMessage.success('记忆已确认，可在新会话中检索')
    memoryCandidates.value.splice(index, 1)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function openTrace() {
  if (currentTraceId.value) {
    router.push({ path: '/traces', query: { traceId: currentTraceId.value } })
  }
}

watch(messages, highlightCodeBlocks, { deep: true })
onMounted(async () => {
  await Promise.all([loadConversations(), loadKnowledgeBases()])
  const queryConversation = typeof route.query.conversationId === 'string' ? route.query.conversationId : ''
  if (queryConversation) {
    const item = conversations.value.find((conversation) => conversation.conversationId === queryConversation)
    if (item) {
      await selectConversation(item)
    }
  }
})
</script>

<template>
  <div class="chat-page">
    <aside class="chat-sidebar">
      <div class="chat-sidebar-head">
        <h3>会话</h3>
        <el-button text type="primary" @click="newConversation">新会话</el-button>
      </div>
      <div class="conversation-list">
        <div
          v-for="item in conversations"
          :key="item.conversationId"
          class="conversation-item"
          :class="{ active: item.conversationId === conversationId }"
          @click="selectConversation(item)"
        >
          <div class="title">{{ item.title || '未命名会话' }}</div>
          <div class="meta">
            <span>{{ formatTime(item.updatedAt) }}</span>
            <el-button text size="small" :icon="Delete" @click.stop="deleteConversation(item)" />
          </div>
        </div>
        <div v-if="!conversations.length" class="empty-state" style="padding: 30px 12px">
          <div class="empty-icon"><el-icon><ChatDotRound /></el-icon></div>
          <div style="font-size: 12px">还没有会话，开始第一轮提问。</div>
        </div>
      </div>
    </aside>

    <section class="chat-main">
      <div class="chat-main-head">
        <div style="min-width: 0">
          <h3 class="truncate">{{ currentTitle }}</h3>
          <div v-if="conversationId" class="mono truncate" style="color: var(--tm-ink-soft); font-size: 10px">
            {{ conversationId }}
          </div>
        </div>
        <div style="display: flex; gap: 6px; align-items: center">
          <el-button text :icon="MagicStick" :loading="extracting" :disabled="!conversationId" @click="extractMemories">
            提取记忆
          </el-button>
          <el-button text :icon="Refresh" @click="loadConversations">刷新</el-button>
        </div>
      </div>

      <div class="chat-toolbar">
        <el-select
          v-model="selectedKbIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="选择知识库（可多选）"
          class="kb-select"
          :max-collapse-tags="1"
        >
          <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-tag v-if="!selectedKbIds.length" type="info" effect="plain" round>未选知识库时按普通对话处理</el-tag>
        <el-tag v-else type="success" effect="plain" round>已选 {{ selectedKbIds.length }} 个知识库</el-tag>
      </div>

      <div ref="scrollContainer" v-loading="loadingConversation" class="chat-scroll">
        <div v-if="!messages.length" class="empty-state" style="padding-top: 60px">
          <div class="empty-icon" style="width: 64px; height: 64px; border-radius: 20px">
            <el-icon><ChatDotRound /></el-icon>
          </div>
          <h3 style="margin: 0 0 8px; color: var(--tm-ink)">问一个研发问题</h3>
          <p class="page-sub" style="max-width: 520px; margin: 0 auto 20px">
            选择知识库后，Agent 会先做任务理解，再按需调用知识库检索、文档详情、长期记忆等工具。
          </p>
          <div style="display: flex; gap: 8px; flex-wrap: wrap; justify-content: center">
            <el-button @click="usePrompt('订单模块的最大连接数是多少？')">订单模块连接数</el-button>
            <el-button @click="usePrompt('我们之前是怎么解决连接池问题的？')">历史经验</el-button>
            <el-button @click="usePrompt('帮我查找哪些文档提到超时配置')">查找文档</el-button>
          </div>
        </div>

        <div v-for="(message, index) in messages" :key="index" class="message-row" :class="message.role">
          <div class="message-avatar">{{ message.role === 'user' ? '你' : 'AI' }}</div>
          <div class="message-body">
            <div v-if="message.role === 'user'" class="message-bubble" style="white-space: pre-wrap">
              {{ message.content }}
            </div>
            <div v-else class="message-bubble">
              <div v-if="message.content" class="markdown-body" v-html="renderMarkdown(message.content)" />
              <div v-else style="color: var(--tm-ink-soft)">
                <el-icon class="is-loading" style="margin-right: 6px"><Refresh /></el-icon>
                正在执行 Agent…
              </div>
              <div v-if="message.citations?.length" class="citation-chips">
                <span
                  v-for="citation in message.citations"
                  :key="citation.index"
                  class="citation-chip"
                  @click="selectCitation(citation.index)"
                >
                  [{{ citation.index }}] {{ citation.documentName }}
                </span>
              </div>
            </div>
            <div v-if="message.role === 'assistant'" class="message-meta">
              <el-tag v-if="message.taskType" size="small" effect="plain" round>{{ message.taskType }}</el-tag>
              <el-tag
                v-if="message.termination"
                size="small"
                :type="statusTagType(message.termination === 'COMPLETED' ? 'SUCCESS' : 'FAILED')"
                effect="plain"
                round
              >
                {{ message.termination }}
              </el-tag>
              <span v-if="message.usage">
                Token {{ message.usage.inputTokens + message.usage.outputTokens }}
              </span>
              <span v-if="message.traceId" class="mono" style="cursor: pointer" @click="openTrace">
                trace {{ message.traceId.slice(0, 8) }}…
              </span>
            </div>
            <el-alert
              v-if="message.warnings?.length"
              type="warning"
              :closable="false"
              show-icon
              style="margin-top: 8px"
              :title="message.warnings.join('；')"
            />
          </div>
        </div>
      </div>

      <div class="chat-input">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="输入问题，Enter 发送，Shift + Enter 换行"
          :disabled="streaming"
          @keydown="handleKeydown"
        />
        <div class="chat-input-actions">
          <div class="form-hint">
            {{ streaming ? 'Agent 正在执行，可随时停止' : '回答会基于真实证据并返回引用与 Trace ID' }}
          </div>
          <div style="display: flex; gap: 8px">
            <el-button v-if="streaming" :icon="VideoPause" @click="stop">停止</el-button>
            <el-button type="primary" :icon="Promotion" :disabled="!canSend" :loading="streaming" @click="send">
              发送
            </el-button>
          </div>
        </div>
      </div>
    </section>

    <aside class="chat-context">
      <el-tabs v-model="activeContextTab" class="chat-context-tabs">
        <el-tab-pane name="evidence">
          <template #label>
            <span>证据 <el-badge :value="citations.length" :hidden="!citations.length" /></span>
          </template>
          <div class="chat-context-body">
            <div v-if="!citations.length" class="empty-state" style="padding: 34px 12px">
              <div class="empty-icon"><el-icon><View /></el-icon></div>
              <div style="font-size: 12px">本轮回答还没有引用证据。</div>
            </div>
            <div
              v-for="citation in citations"
              :key="citation.index"
              class="citation-card"
              @click="selectCitation(citation.index)"
            >
              <div class="citation-head">
                <span>[{{ citation.index }}] {{ citation.documentName }}</span>
                <el-tag size="small" effect="plain" round>v{{ citation.documentVersion }}</el-tag>
              </div>
              <div class="span-meta">
                <span v-if="citation.pageNumber">第 {{ citation.pageNumber }} 页</span>
                <span>score {{ citation.score.toFixed(3) }}</span>
                <span v-if="citation.rerankScore != null">rerank {{ citation.rerankScore.toFixed(3) }}</span>
              </div>
              <div
                class="citation-body"
                :style="expandedCitation === citation.index ? 'max-height: none' : ''"
              >
                {{ citation.content }}
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane name="execution">
          <template #label>
            <span>执行 <el-badge :value="steps.length" :hidden="!steps.length" /></span>
          </template>
          <div class="chat-context-body">
            <el-alert
              v-if="summaryText"
              type="info"
              :closable="false"
              show-icon
              title="会话摘要"
              :description="summaryText"
              style="margin-bottom: 12px"
            />

            <div v-if="task" style="margin-bottom: 14px">
              <div style="display: flex; gap: 6px; align-items: center; flex-wrap: wrap">
                <el-tag type="success" effect="light" round>{{ task.taskType }}</el-tag>
                <el-tag type="info" effect="plain" round>{{ task.ruleBased ? '规则' : 'LLM' }}</el-tag>
                <span class="form-hint">confidence {{ task.confidence.toFixed(2) }}</span>
              </div>
              <div class="span-summary">{{ task.userIntent }}</div>
              <div v-if="task.requiredTools.length" class="span-meta">
                <span v-for="tool in task.requiredTools" :key="tool" class="mono">{{ tool }}</span>
              </div>
            </div>

            <div v-if="!steps.length" class="empty-state" style="padding: 24px 8px">
              <div style="font-size: 12px">本轮还没有执行步骤。</div>
            </div>
            <div
              v-for="step in steps"
              :key="step.index"
              class="step-item"
              :class="{ failed: !step.success }"
            >
              <div class="step-type">
                {{ step.type }}
                <span v-if="step.name"> · {{ step.name }}</span>
                <span v-if="step.durationMillis"> · {{ step.durationMillis }} ms</span>
              </div>
              <div class="step-content">{{ step.content }}</div>
            </div>

            <div v-if="currentTraceId" class="citation-card" style="margin-top: 14px">
              <div class="citation-head">
                <span>Trace</span>
                <el-button text type="primary" size="small" @click="openTrace">查看详情</el-button>
              </div>
              <div class="mono" style="margin-top: 6px; word-break: break-all">{{ currentTraceId }}</div>
              <div v-if="usage" class="span-meta">
                <span>input {{ usage.inputTokens }}</span>
                <span>output {{ usage.outputTokens }}</span>
                <span>total {{ usage.inputTokens + usage.outputTokens }}</span>
              </div>
            </div>
            <el-alert
              v-if="warnings.length"
              type="warning"
              :closable="false"
              show-icon
              style="margin-top: 12px"
              :title="warnings.join('；')"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </aside>
  </div>

  <el-dialog v-model="memoryDialogVisible" title="长期记忆候选" width="680px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="候选必须确认后才会变为 ACTIVE，并在新会话中被 Agent 检索。"
      style="margin-bottom: 12px"
    />
    <div v-if="memoryWarnings.length" class="form-hint" style="margin-bottom: 10px">
      {{ memoryWarnings.join('；') }}
    </div>
    <div v-if="!memoryCandidates.length" class="empty-state" style="padding: 24px">
      没有可确认的候选。
    </div>
    <div v-for="(candidate, index) in memoryCandidates" :key="candidate.id" class="citation-card">
      <div class="citation-head">
        <span>{{ candidate.title || '未命名记忆' }}</span>
        <el-tag size="small" effect="plain" round>{{ candidate.memoryType }}</el-tag>
      </div>
      <div class="citation-body">{{ candidate.content }}</div>
      <div class="span-meta">
        <span v-if="candidate.sourceConversationId">来源会话 {{ candidate.sourceConversationId }}</span>
        <span v-if="candidate.sourceTraceId">Trace {{ candidate.sourceTraceId.slice(0, 8) }}…</span>
        <span v-if="candidate.confidence != null">confidence {{ candidate.confidence.toFixed(2) }}</span>
      </div>
      <div style="margin-top: 10px; text-align: right">
        <el-button size="small" type="primary" @click="confirmCandidate(index)">确认并启用</el-button>
      </div>
    </div>
  </el-dialog>
</template>
