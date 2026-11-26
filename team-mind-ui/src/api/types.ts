export type Id = string | number

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  path?: string
  timestamp?: string
  errors?: Array<{ field: string; message: string }>
}

export interface ModelUsage {
  inputTokens: number
  outputTokens: number
}

export interface KnowledgeBase {
  id: Id
  name: string
  description?: string | null
  ownerId: Id
  status: number
  createdAt: string
  updatedAt: string
}

export type ProcessingStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'

export interface DocumentItem {
  id: Id
  knowledgeBaseId: Id
  documentName: string
  fileType: string
  filePath?: string
  currentVersion: number
  processingStatus: ProcessingStatus
  failureReason?: string | null
  createdAt: string
  updatedAt: string
}

export interface DashboardStats {
  knowledgeBaseCount: number
  documentCount: number
  conversationCount: number
  memoryCount: number
  traceCount: number
  evaluationRunCount: number
  failedTraceCount: number
  recentTraces: TraceSummary[]
}

export interface Citation {
  index: number
  chunkId: Id
  documentId: Id
  knowledgeBaseId: Id
  documentName: string
  documentVersion: number
  pageNumber?: number | null
  content: string
  score: number
  denseRank?: number | null
  bm25Rank?: number | null
  denseScore?: number | null
  bm25Score?: number | null
  rrfScore?: number | null
  rerankScore?: number | null
}

export type StepType = 'THOUGHT' | 'ACTION' | 'OBSERVATION' | 'FINAL_ANSWER'

export interface AgentStep {
  index: number
  type: StepType
  name?: string | null
  content: string
  arguments: Record<string, unknown>
  success: boolean
  durationMillis: number
}

export interface TaskUnderstanding {
  taskType: string
  userIntent: string
  entities: string[]
  keywords: string[]
  knowledgeBaseIds: Id[]
  timeRange?: { start?: string | null; end?: string | null } | null
  requiredTools: string[]
  confidence: number
  ruleBased: boolean
  warnings: string[]
  usage?: ModelUsage
}

export interface AgentAnswer {
  answer: string
  citations: Citation[]
  traceId: string
  taskType: string
  taskUnderstanding: TaskUnderstanding
  steps: AgentStep[]
  termination: string
  warnings: string[]
  usage: ModelUsage
  conversationId?: string | null
}

export interface AgentChatRequest {
  message: string
  knowledgeBaseIds: Id[]
  ownerId: Id
  topK?: number
  conversationId?: string | null
  history?: Array<{ role: 'user' | 'assistant'; content: string }>
  documentIds?: Id[]
}

export interface StoredMessage {
  role: 'user' | 'assistant'
  content: string
  traceId?: string | null
  createdAt?: string
}

export interface ConversationSummary {
  summary: string
  summarizedMessages: number
  updatedAt: string
}

export interface ConversationMessages {
  conversationId: string
  title: string
  summary?: ConversationSummary | null
  messages: StoredMessage[]
}

export interface ConversationItem {
  conversationId: string
  title?: string | null
  createdAt: string
  updatedAt: string
}

export type MemoryStatus = 'DRAFT' | 'ACTIVE' | 'INVALID'
export type MemoryType = 'FACT' | 'PREFERENCE' | 'DECISION' | 'EXPERIENCE' | 'PROFILE' | 'OTHER'

export interface MemoryItem {
  id: Id
  ownerId: Id
  memoryType: MemoryType
  title: string
  content: string
  summary?: string | null
  sourceConversationId?: string | null
  sourceTraceId?: string | null
  version: number
  status: MemoryStatus
  confidence?: number | null
  lastUsedAt?: string | null
  createdAt: string
  updatedAt: string
}

export interface MemoryExtractionResponse {
  memories: MemoryItem[]
  warnings: string[]
}

export interface TraceSummary {
  id?: Id
  traceId: string
  userId?: Id | null
  conversationId?: string | null
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT' | string
  totalDuration?: number | null
  totalTokens?: number | null
  promptTokens?: number | null
  completionTokens?: number | null
  strategy?: string | null
  query?: string | null
  errorMessage?: string | null
  createdAt?: string
  finishedAt?: string | null
}

export interface TraceSpan {
  id?: Id
  traceId: string
  spanId: string
  parentSpanId?: string | null
  spanType: string
  status: string
  duration?: number | null
  inputTokens?: number | null
  outputTokens?: number | null
  errorType?: string | null
  errorMessage?: string | null
  inputSummary?: string | null
  outputSummary?: string | null
  createdAt?: string
}

export interface TraceDetail {
  trace: TraceSummary
  spans: TraceSpan[]
}

export interface EvidenceItem {
  id: Id
  traceId: string
  ownerId: Id
  documentId: Id
  chunkId: Id
  documentName: string
  documentVersion: number
  content: string
  pageNumber?: number | null
  retrievalType: string
  denseRank?: number | null
  bm25Rank?: number | null
  denseScore?: number | null
  bm25Score?: number | null
  rrfScore?: number | null
  rerankScore?: number | null
  reranked?: boolean
  cited?: boolean
  createdAt?: string
}

export interface DatasetDescriptor {
  type: string
  name: string
  version: string
  caseCount: number
  hash: string
}

export interface EvaluationCaseResult {
  caseId: string
  query: string
  success: boolean
  expected: Record<string, unknown>
  actual: Record<string, unknown>
  metrics: Record<string, unknown>
  errorMessage?: string | null
  durationMillis: number
}

export interface EvaluationReport {
  runId: string
  datasetType: string
  datasetName: string
  datasetHash: string
  status: string
  caseCount: number
  metrics: Record<string, unknown>
  cases: EvaluationCaseResult[]
  reportMarkdown: string
  startedAt?: string
  finishedAt?: string
}

export interface EvaluationRunSummary {
  runId: string
  datasetType: string
  datasetName: string
  datasetHash: string
  status: string
  caseCount: number
  metrics: Record<string, unknown>
  errorMessage?: string | null
  startedAt?: string
  finishedAt?: string
}

export interface EvaluationRunDetail {
  run: EvaluationRunSummary
  cases: EvaluationCaseResult[]
}
