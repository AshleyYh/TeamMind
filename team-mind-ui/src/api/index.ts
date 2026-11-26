import { apiRequest } from './http'
import type {
  AgentAnswer,
  AgentChatRequest,
  ConversationItem,
  ConversationMessages,
  DashboardStats,
  DatasetDescriptor,
  DocumentItem,
  EvaluationReport,
  EvaluationRunDetail,
  EvaluationRunSummary,
  EvidenceItem,
  Id,
  KnowledgeBase,
  MemoryExtractionResponse,
  MemoryItem,
  MemoryStatus,
  MemoryType,
  TaskUnderstanding,
  TraceDetail,
  TraceSpan,
  TraceSummary,
  AgentStep,
} from './types'

export const api = {
  dashboard(ownerId: Id) {
    return apiRequest<DashboardStats>({ url: '/dashboard/stats', params: { ownerId } })
  },

  listKnowledgeBases(ownerId: Id) {
    return apiRequest<KnowledgeBase[]>({ url: '/knowledge-bases', params: { ownerId } })
  },
  createKnowledgeBase(payload: { name: string; description?: string; ownerId: Id }) {
    return apiRequest<KnowledgeBase>({ url: '/knowledge-bases', method: 'POST', data: payload })
  },
  updateKnowledgeBase(id: Id, payload: { name: string; description?: string; ownerId: Id }) {
    return apiRequest<KnowledgeBase>({ url: `/knowledge-bases/${id}`, method: 'PUT', data: payload })
  },
  deleteKnowledgeBase(id: Id, ownerId: Id) {
    return apiRequest<void>({ url: `/knowledge-bases/${id}`, method: 'DELETE', params: { ownerId } })
  },

  listDocuments(knowledgeBaseId: Id, ownerId: Id) {
    return apiRequest<DocumentItem[]>({ url: '/documents', params: { knowledgeBaseId, ownerId } })
  },
  uploadDocument(knowledgeBaseId: Id, ownerId: Id, file: File) {
    const form = new FormData()
    form.append('knowledgeBaseId', String(knowledgeBaseId))
    form.append('ownerId', String(ownerId))
    form.append('file', file)
    return apiRequest<DocumentItem>({
      url: '/documents/upload',
      method: 'POST',
      data: form,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  documentStatus(id: Id, ownerId: Id) {
    return apiRequest<DocumentItem>({ url: `/documents/${id}/status`, params: { ownerId } })
  },
  reprocessDocument(id: Id, ownerId: Id) {
    return apiRequest<DocumentItem>({ url: `/documents/${id}/reprocess`, method: 'POST', params: { ownerId } })
  },
  deleteDocument(id: Id, ownerId: Id) {
    return apiRequest<void>({ url: `/documents/${id}`, method: 'DELETE', params: { ownerId } })
  },

  listConversations(ownerId: Id) {
    return apiRequest<ConversationItem[]>({ url: '/conversations', params: { ownerId } })
  },
  conversationMessages(conversationId: string, ownerId: Id, limit = 50) {
    return apiRequest<ConversationMessages>({
      url: `/conversations/${conversationId}/messages`,
      params: { ownerId, limit },
    })
  },
  deleteConversation(conversationId: string, ownerId: Id) {
    return apiRequest<void>({ url: `/conversations/${conversationId}`, method: 'DELETE', params: { ownerId } })
  },
  extractMemories(conversationId: string, ownerId: Id) {
    return apiRequest<MemoryExtractionResponse>({
      url: `/conversations/${conversationId}/memories/extract`,
      method: 'POST',
      params: { ownerId },
    })
  },

  listMemories(params: {
    ownerId: Id
    status?: MemoryStatus
    type?: MemoryType
    limit?: number
  }) {
    return apiRequest<MemoryItem[]>({ url: '/memories', params })
  },
  searchMemories(ownerId: Id, query: string, limit = 10) {
    return apiRequest<MemoryItem[]>({ url: '/memories/search', params: { ownerId, query, limit } })
  },
  createMemory(payload: {
    ownerId: Id
    memoryType: MemoryType
    title?: string
    content: string
    summary?: string
    confidence?: number
  }) {
    return apiRequest<MemoryItem>({ url: '/memories', method: 'POST', data: payload })
  },
  updateMemory(id: Id, payload: {
    ownerId: Id
    memoryType?: MemoryType
    title?: string
    content: string
    summary?: string
  }) {
    return apiRequest<MemoryItem>({ url: `/memories/${id}`, method: 'PUT', data: payload })
  },
  confirmMemory(id: Id, ownerId: Id) {
    return apiRequest<MemoryItem>({ url: `/memories/${id}/confirm`, method: 'POST', params: { ownerId } })
  },
  invalidateMemory(id: Id, ownerId: Id) {
    return apiRequest<MemoryItem>({ url: `/memories/${id}/invalidate`, method: 'POST', params: { ownerId } })
  },
  deleteMemory(id: Id, ownerId: Id) {
    return apiRequest<void>({ url: `/memories/${id}`, method: 'DELETE', params: { ownerId } })
  },

  listTraces(ownerId: Id, limit = 20) {
    return apiRequest<TraceSummary[]>({ url: '/traces', params: { ownerId, limit } })
  },
  traceDetail(traceId: string, ownerId: Id) {
    return apiRequest<TraceDetail>({ url: `/traces/${traceId}`, params: { ownerId } })
  },
  traceSpans(traceId: string, ownerId: Id) {
    return apiRequest<TraceSpan[]>({ url: `/traces/${traceId}/spans`, params: { ownerId } })
  },
  traceEvidences(traceId: string, ownerId: Id) {
    return apiRequest<EvidenceItem[]>({ url: `/traces/${traceId}/evidences`, params: { ownerId } })
  },

  datasets() {
    return apiRequest<DatasetDescriptor[]>({ url: '/evaluation/datasets' })
  },
  evaluateTasks(payload: { ownerId: Id; dataset?: string; knowledgeBaseIds?: Id[] }) {
    return apiRequest<EvaluationReport>({ url: '/evaluation/tasks', method: 'POST', data: payload })
  },
  evaluateRag(payload: {
    ownerId: Id
    knowledgeBaseIds: Id[]
    strategies?: string[]
    topK?: number
    dataset?: string
    evaluateAnswers?: boolean
  }) {
    return apiRequest<EvaluationReport>({ url: '/evaluation/rag', method: 'POST', data: payload })
  },
  evaluateMemories(payload: { ownerId: Id; topK?: number; dataset?: string }) {
    return apiRequest<EvaluationReport>({ url: '/evaluation/memories', method: 'POST', data: payload })
  },
  evaluationRuns(ownerId: Id, limit = 20) {
    return apiRequest<EvaluationRunSummary[]>({ url: '/evaluation/runs', params: { ownerId, limit } })
  },
  evaluationRunDetail(runId: string, ownerId: Id) {
    return apiRequest<EvaluationRunDetail>({ url: `/evaluation/runs/${runId}`, params: { ownerId } })
  },
  evaluationReport(runId: string, ownerId: Id) {
    return apiRequest<string>({ url: `/evaluation/runs/${runId}/report`, params: { ownerId }, responseType: 'text' })
  },
}

export interface StreamHandlers {
  onTrace?: (traceId: string) => void
  onTask?: (task: TaskUnderstanding) => void
  onStep?: (step: AgentStep) => void
  onDelta?: (delta: string) => void
  onDone?: (answer: AgentAnswer) => void
  onError?: (error: { code?: string; message: string; traceId?: string | null }) => void
}

/**
 * POST SSE：EventSource 不支持 POST，因此使用 fetch + ReadableStream 手动解析事件。
 */
export async function streamAgentChat(
  payload: AgentChatRequest,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(payload),
    signal,
  })
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const body = await response.json()
      message = body?.message || message
    } catch (_) {
      // 非 JSON 错误响应保持 HTTP 状态文案。
    }
    throw new Error(message)
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const dispatch = (block: string) => {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    if (!dataLines.length) {
      return
    }
    const raw = dataLines.join('\n')
    let data: unknown = raw
    try {
      data = JSON.parse(raw)
    } catch (_) {
      // 纯文本事件保留原始字符串。
    }
    if (event === 'start') {
      handlers.onTrace?.((data as { traceId?: string })?.traceId || '')
    } else if (event === 'task') {
      handlers.onTask?.(data as TaskUnderstanding)
    } else if (event === 'step') {
      handlers.onStep?.(data as AgentStep)
    } else if (event === 'answer_delta') {
      handlers.onDelta?.((data as { delta?: string })?.delta || '')
    } else if (event === 'done') {
      handlers.onDone?.(data as AgentAnswer)
    } else if (event === 'error') {
      const error = data as { code?: string; message?: string; traceId?: string | null }
      handlers.onError?.({
        code: error?.code,
        message: error?.message || 'Agent 执行失败',
        traceId: error?.traceId,
      })
    }
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        if (block.trim()) {
          dispatch(block)
        }
        boundary = buffer.indexOf('\n\n')
      }
    }
    if (buffer.trim()) {
      dispatch(buffer)
    }
  } finally {
    reader.releaseLock()
  }
}
