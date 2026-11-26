package com.teammind.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.common.api.ApiResponse;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.evaluation.application.EvaluationRunService;
import com.teammind.evaluation.application.MemoryEvaluationService;
import com.teammind.evaluation.application.RagEvaluationService;
import com.teammind.evaluation.application.TaskEvaluationService;
import com.teammind.evaluation.dataset.DatasetLoader;
import com.teammind.evaluation.domain.EvaluationCase;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.report.EvaluationReport;
import com.teammind.knowledge.api.SearchStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评测接口：加载内置数据集，执行任务识别、RAG 检索或长期记忆评测，
 * 保存运行结果并输出 Markdown 报告。
 */
@Tag(name = "评测", description = "任务识别、RAG 检索和长期记忆评测，支持 Recall@K、MRR、NDCG、任务准确率和报告追溯。")
@Validated
@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {
    private final EvaluationRunService runService;
    private final TaskEvaluationService taskEvaluationService;
    private final RagEvaluationService ragEvaluationService;
    private final MemoryEvaluationService memoryEvaluationService;
    private final DatasetLoader datasetLoader;
    private final ObjectMapper objectMapper;

    public EvaluationController(EvaluationRunService runService, TaskEvaluationService taskEvaluationService,
            RagEvaluationService ragEvaluationService, MemoryEvaluationService memoryEvaluationService,
            DatasetLoader datasetLoader, ObjectMapper objectMapper) {
        this.runService = runService;
        this.taskEvaluationService = taskEvaluationService;
        this.ragEvaluationService = ragEvaluationService;
        this.memoryEvaluationService = memoryEvaluationService;
        this.datasetLoader = datasetLoader;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "查询内置评测数据集", description = "返回任务识别、RAG 检索和记忆检索数据集名称、版本、用例数和 SHA-256。")
    @GetMapping("/datasets")
    public ApiResponse<List<DatasetLoader.DatasetDescriptor>> datasets() {
        return ApiResponse.success(datasetLoader.list());
    }

    @Operation(summary = "任务识别评测", description = "对数据集执行任务分类和工具选择评测，输出准确率、工具命中率和逐用例结果。")
    @PostMapping("/tasks")
    public ApiResponse<EvaluationReport> evaluateTasks(@Valid @RequestBody TaskEvaluationRequest request) {
        return ApiResponse.success(taskEvaluationService.evaluate(request.ownerId(), request.dataset(),
                request.knowledgeBaseIds()));
    }

    @Operation(summary = "RAG 检索评测", description = """
            对同一数据集分别执行 DENSE、BM25、HYBRID、HYBRID_RERANK，计算 Recall@K、MRR、NDCG@K。
            evaluateAnswers=true 时会调用对话模型生成答案，并计算引用精确率、引用召回率和答案关键词覆盖率，消耗模型配额。
            """)
    @PostMapping("/rag")
    public ApiResponse<EvaluationReport> evaluateRag(@Valid @RequestBody RagEvaluationRequest request) {
        return ApiResponse.success(ragEvaluationService.evaluate(request.ownerId(),
                request.knowledgeBaseIds(), request.strategies(), request.topK(), request.dataset(),
                Boolean.TRUE.equals(request.evaluateAnswers())));
    }

    @Operation(summary = "长期记忆检索评测", description = "对当前用户已确认的 ACTIVE 记忆执行关键词检索评测，计算 Recall@K、MRR、NDCG@K。")
    @PostMapping("/memories")
    public ApiResponse<EvaluationReport> evaluateMemories(
            @Valid @RequestBody MemoryEvaluationRequest request) {
        return ApiResponse.success(memoryEvaluationService.evaluate(request.ownerId(), request.topK(),
                request.dataset()));
    }

    @Operation(summary = "查询评测运行列表", description = "按归属用户返回历史评测运行，包含聚合指标和数据集 hash。")
    @GetMapping("/runs")
    public ApiResponse<List<EvaluationRunSummary>> runs(
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId,
            @Parameter(description = "返回条数") @RequestParam(defaultValue = "20")
            @Min(1) @Max(100) int limit) {
        return ApiResponse.success(runService.list(ownerId, limit).stream()
                .map(this::summary).toList());
    }

    @Operation(summary = "查询评测运行详情", description = "返回运行元数据、聚合指标和逐用例期望/实际/指标。")
    @GetMapping("/runs/{runId}")
    public ApiResponse<EvaluationRunDetail> runDetail(
            @Parameter(description = "32 位十六进制评测运行 ID") @PathVariable
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String runId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        EvaluationRun run = runService.get(runId, ownerId);
        List<EvaluationCaseResponse> cases = runService.cases(runId, ownerId).stream()
                .map(this::caseResponse).toList();
        return ApiResponse.success(new EvaluationRunDetail(summary(run), cases));
    }

    @Operation(summary = "下载 Markdown 评测报告", description = "返回运行保存的 Markdown 报告；尚未完成或没有报告时返回 40401。")
    @GetMapping(value = "/runs/{runId}/report", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(
            @Parameter(description = "32 位十六进制评测运行 ID") @PathVariable
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String runId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        EvaluationRun run = runService.get(runId, ownerId);
        if (run.getReportMarkdown() == null || run.getReportMarkdown().isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评测报告尚未生成");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(run.getReportMarkdown());
    }

    private EvaluationRunSummary summary(EvaluationRun run) {
        return new EvaluationRunSummary(run.getRunId(), run.getDatasetType(), run.getDatasetName(),
                run.getDatasetHash(), run.getStatus(), run.getCaseCount(),
                parseMap(run.getMetricsJson()), run.getErrorMessage(), run.getStartedAt(),
                run.getFinishedAt());
    }

    private EvaluationCaseResponse caseResponse(EvaluationCase item) {
        return new EvaluationCaseResponse(item.getCaseId(), item.getQueryText(),
                Boolean.TRUE.equals(item.getSuccess()), parseMap(item.getExpectedJson()),
                parseMap(item.getActualJson()), parseMap(item.getMetricsJson()),
                item.getErrorMessage(), item.getDuration() == null ? 0 : item.getDuration());
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of("raw", json);
        }
    }

    /** 任务评测请求。 */
    public record TaskEvaluationRequest(
            @Schema(description = "开发期归属用户 ID", example = "1")
            @NotNull @Positive Long ownerId,
            @Schema(description = "内置数据集名称，不传使用 task-classification", example = "task-classification")
            @Size(max = 64) String dataset,
            @Schema(description = "可选知识库范围；不传时使用数据集中的范围")
            @Size(max = 20) List<@NotNull @Positive Long> knowledgeBaseIds) {
    }

    /** RAG 评测请求。 */
    public record RagEvaluationRequest(
            @Schema(description = "开发期归属用户 ID", example = "1")
            @NotNull @Positive Long ownerId,
            @Schema(description = "参与评测的知识库 ID，至少一个")
            @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> knowledgeBaseIds,
            @Schema(description = "要比较的检索策略；不传时比较四种策略")
            @Size(max = 4) List<SearchStrategy> strategies,
            @Schema(description = "Recall@K 的 K，默认 5", example = "5")
            @Min(1) @Max(20) Integer topK,
            @Schema(description = "内置数据集名称，不传使用 rag-retrieval", example = "rag-retrieval")
            @Size(max = 64) String dataset,
            @Schema(description = "是否调用对话模型评测答案和引用，默认 false", example = "false")
            Boolean evaluateAnswers) {
    }

    /** 长期记忆评测请求。 */
    public record MemoryEvaluationRequest(
            @Schema(description = "开发期归属用户 ID", example = "1")
            @NotNull @Positive Long ownerId,
            @Schema(description = "检索条数，默认 5")
            @Min(1) @Max(20) Integer topK,
            @Schema(description = "内置数据集名称，不传使用 memory-retrieval", example = "memory-retrieval")
            @Size(max = 64) String dataset) {
    }

    /** 评测运行摘要。 */
    public record EvaluationRunSummary(String runId, String datasetType, String datasetName,
            String datasetHash, String status, int caseCount, Map<String, Object> metrics,
            String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 评测运行详情。 */
    public record EvaluationRunDetail(EvaluationRunSummary run, List<EvaluationCaseResponse> cases) {
    }

    /** 评测用例结果。 */
    public record EvaluationCaseResponse(String caseId, String query, boolean success,
            Map<String, Object> expected, Map<String, Object> actual, Map<String, Object> metrics,
            String errorMessage, long durationMillis) {
    }
}
