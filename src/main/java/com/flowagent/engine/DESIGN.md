# FlowAgent Engine Design Notes

## 1. DAG Engine Selection

### Why a custom DAG engine over a general-purpose workflow framework?

| Consideration | Custom DAG | General Framework (e.g., Camunda, Activiti) |
|---|---|---|
| DSL flexibility | JSON DSL with arbitrary node types | BPMN XML, limited to predefined constructs |
| LLM streaming | SSE events per node, real-time token output | Not designed for streaming callbacks |
| Variable passing | Instance-scoped WorkflowContextStore, dot-path resolution | Process variables with rigid scoping |
| Branch routing | `fail_` prefix convention for error-condition edges | Explicit gateway nodes, verbose |
| Topology validation | Kahn algorithm O(V+E), early cycle detection | Runtime validation, no static guarantee |

The custom engine trades BPMN standardization for **dynamic LLM orchestration flexibility** — the primary use case is AI agent workflow, not human task routing.

---

## 2. Dual-Engine Architecture

### Design Philosophy

The `WorkflowExecutionEngine` interface decouples orchestration logic from execution mechanics:

```
WorkflowExecutionEngine (interface)
  ├── LegacyDagEngine
  │     ├── DagWorkflowEngine    (SEQUENTIAL — DFS)
  │     └── ParallelWorkflowEngine (PARALLEL — BFS + CompletableFuture)
  └── LangGraphEngine
        └── LangGraph4j StateGraph with conditional edges
```

**LegacyDagEngine** is the primary engine. It supports two scheduling modes:
- **SEQUENTIAL**: DFS traversal, each node waits for predecessors to complete. Simple, predictable, easy to debug.
- **PARALLEL**: BFS with `CompletableFuture`, fork-join pattern for independent nodes. Uses bounded thread pool with `CallerRunsPolicy` for backpressure.

**LangGraphEngine** is an adapter wrapping LangGraph4j's `StateGraph`. It maps our `NodeTypeEnum` / `WorkflowContextStore` / `WorkflowNodeHandler` into LangGraph's node/state/channel model. This allows teams already using LangGraph to migrate seamlessly.

### Routing

`EngineFactory` uses `EngineType` + `ExecutionMode` configuration to select the concrete engine:

```java
EngineType.LEGACY  + ExecutionMode.SEQUENTIAL  → DagWorkflowEngine
EngineType.LEGACY  + ExecutionMode.PARALLEL    → ParallelWorkflowEngine
EngineType.LANGGRAPH                             → LangGraphEngine
```

---

## 3. BFS Parallel Scheduling

### Fork-Join Pattern

```
          ┌─── node-A ───┐
START ────┤               ├─── END
          └─── node-B ───┘
```

When a node has multiple successors with no inter-dependency, the parallel engine submits them as `CompletableFuture` tasks to the bounded thread pool:

```java
// Fork: submit independent successors
List<CompletableFuture<Void>> futures = successors.stream()
    .map(node -> CompletableFuture.runAsync(
        () -> executeNode(node, contextStore, callback), executorService))
    .toList();

// Join: wait for all to complete
CompletableFuture.allOf(futures).get(workflowTimeout, TimeUnit.SECONDS);
```

### Thread Pool Configuration

| Parameter | Default | Purpose |
|---|---|---|
| `core-pool-size` | 16 | IO-intensive: CPU cores × 2 |
| `max-pool-size` | 50 | Ceiling for burst traffic |
| `queue-capacity` | 200 | LinkedBlockingQueue buffer |
| `keep-alive-seconds` | 60 | Idle thread reclaim |
| `rejected-execution-handler` | CallerRunsPolicy | Backpressure: caller thread executes the task |

`TtlExecutors.getTtlExecutorService()` wraps the pool to propagate `EngineContextHolder` across threads.

---

## 4. Instance-Scoped Variable Pool

### Why instance-scoped instead of global?

Each workflow execution gets its own `WorkflowContextStore` instance:
- **No shared state between workflows** — eliminates OOM risk from unbounded global accumulation
- **Natural lifecycle** — GC reclaim when execution completes
- **Thread-safe** — `ConcurrentHashMap` backing with TTL context propagation

### Variable Path Resolution

```
{{node-start::001.outputs.query}}      → direct dot-path
{{llm::002.outputs.choices[0].text}}   → array-index path
{{llm::002.nodeParam.model}}           → node parameter fallback
```

`VariablePathResolver` resolves `nodeId.path` notation with optional array indexing. `VariableTemplateRender` replaces `{{...}}` placeholders using regex `\{\{([^}]+)\}\}`.

---

## 5. Token Sliding Window

`LlmChatHistory` maintains per-node conversation history in a Guava `LoadingCache` with configurable token budget:

```
maxContextTokens = 8192
estimated chars per token ≈ 4
budget = 8192 × 4 = 32,768 characters
```

When accumulated history exceeds the budget, oldest user/assistant turns are evicted while preserving the current system prompt and most recent N turns. This prevents LLM API token-limit overflow during long conversations.

---

## 6. Node Timeout & Error Strategy

### Timeout Fallback

`AbstractNodeHandler.doExecuteWithTimeout()` wraps node execution with `AsyncUtil.callWithTimeLimit()`:

| Condition | Behavior |
|---|---|
| Success within timeout | Return `NodeRunResult` with output |
| `TimeoutException` | Log WARN, fall through to error strategy |
| `InterruptedException` | Log WARN, set `ERR_INTERRUPT` result |
| Unhandled exception | Log ERROR, wrap in `NodeCustomException` |

### Three Error Strategies

| Strategy | Config Value | Downstream Effect |
|---|---|---|
| **ERR_CODE** | `err_code` | Produce custom error output, successor nodes receive it via variable pool |
| **ERR_CONDITION** | `err_condition` | Route to fail-branch nodes (edges with `sourceHandle` starting with `fail_`) |
| **ERR_INTERRUPT** | `err_interrupt` | Abort entire workflow, propagate error to caller |

### MARK → SKIP Normalization

When a branch fails and sibling branches are no longer reachable, their status transitions:

```
INIT → MARK (marked for potential execution, but parent failed)
MARK → SKIP (normalized: unreachable, skipped in final result)
```

`normalizeMarkNodes()` traverses the graph after execution, converting all MARK nodes to SKIP for a clean status report.

---

## 7. Busy-Wait Elimination

`WorkflowMsgCallback` consumer thread originally polled `ConcurrentLinkedQueue` with `sleep(10)`:
- CPU waste during idle periods
- Latency between message availability and consumption (up to 10ms)

Replaced with `LinkedBlockingQueue.take()`:
- Thread blocks until a message arrives — zero CPU waste
- `POISON_PILL` sentinel terminates the consumer thread gracefully at workflow end
- Direct `Thread` creation avoids `CallerRunsPolicy` deadlock risk in the engine's own thread pool
