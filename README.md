# FlowAgent

**Distributed AI Agent workflow orchestration engine** — a Spring Boot backend built around a custom DAG scheduling kernel, supporting dual-engine execution (self-developed DAG + LangGraph4j adapter), JSON DSL dynamic orchestration, SSE streaming output, and pluggable LLM node integration.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    REST API Layer                        │
│  ┌──────────────────┐  ┌──────────────────────────────┐ │
│  │ WorkflowController│  │ ProtocolController (CRUD)   │ │
│  │  SSE /execute     │  │  /save /read /update /del   │ │
│  └──────────────────┘  └──────────────────────────────┘ │
├─────────────────────────────────────────────────────────┤
│                 Engine Abstraction                       │
│  ┌──────────────────────────────────────────────────┐   │
│  │       WorkflowExecutionEngine (interface)         │   │
│  │  ┌──────────────┐  ┌──────────────────────────┐  │   │
│  │  │  LegacyDagEngine│  │  LangGraphEngine       │  │   │
│  │  │  SEQ | PAR    │  │  StateGraph adapter     │  │   │
│  │  └──────────────┘  └──────────────────────────┘  │   │
│  └──────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│                    DAG Kernel                           │
│  ┌────────────────┐ ┌──────────────┐ ┌─────────────┐  │
│  │ TopologyValidator│ │ GraphBuilder │ │ DslParser   │  │
│  │ Kahn cycle check│ │ Build chains │ │ JSON→DSL    │  │
│  └────────────────┘ └──────────────┘ └─────────────┘  │
├─────────────────────────────────────────────────────────┤
│                 Node Handler System                     │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────────┐ ┌─────────┐ │
│  │START │ │ END  │ │ LLM  │ │ IF_ELSE  │ │COND_SW  │ │
│  │Node  │ │Node  │ │Node  │ │  Node    │ │  Node   │ │
│  └──────┘ └──────┘ └──────┘ └──────────┘ └─────────┘ │
├─────────────────────────────────────────────────────────┤
│               Infrastructure                            │
│  ┌───────────────────┐ ┌───────────────────────────┐  │
│  │ WorkflowContextStore│ │ EngineContextHolder (TTL)│  │
│  │ Instance-scoped    │ │ Cross-thread propagation │  │
│  └───────────────────┘ └───────────────────────────┘  │
│  ┌───────────────────┐ ┌───────────────────────────┐  │
│  │  Bounded thread pool│ │  LlmChatHistory (token  │  │
│  │  CallerRunsPolicy  │ │  sliding window)        │  │
│  └───────────────────┘ └───────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│                 Persistence                             │
│  ┌──────────────────────────────────────────────┐      │
│  │  MyBatis-Plus → MySQL (single datasource)    │      │
│  │  WorkflowEntity → flow table (LONGTEXT DSL)  │      │
│  └──────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────┘
```

### Dual-Engine Design

Two axes define the engine architecture:

| Axis | Options | Description |
|---|---|---|
| **EngineType** (execution framework) | `LEGACY` / `LANGGRAPH` | Self-developed DAG or LangGraph4j adapter |
| **ExecutionMode** (scheduling strategy, only for LEGACY) | `SEQUENTIAL` / `PARALLEL` | DFS sequential or BFS parallel within LEGACY engine |

```yaml
workflow:
  engine:
    type: LEGACY      # LEGACY | LANGGRAPH
    mode: SEQUENTIAL   # SEQUENTIAL | PARALLEL (only affects LEGACY)
```

> **Note**: SEQUENTIAL vs PARALLEL are scheduling strategies *within* the LEGACY engine, not separate frameworks.

---

## Tech Stack

- **Java 21** + **Spring Boot 3.5.4**
- **MyBatis-Plus 3.5.7** + MySQL 8.x (single datasource)
- **Spring AI 1.1.2** (OpenAI-style LLM integration, streaming support)
- **LangGraph4j 1.6.0** (StateGraph conditional edges)
- **TransmittableThreadLocal 2.14.5** (cross-thread context propagation)
- **Guava 33.5.0** (LoadingCache for chat history)
- **OkHttp 4.12.0** / **Fastjson2 2.0.51** / **Hutool 5.8.27**

---

## Node Types

| Type | DSL Value | Description |
|---|---|---|
| `START` | `node-start` | Entry point, passes initial inputs to context |
| `END` | `node-end` | Terminal node, aggregates final outputs |
| `LLM` | `llm` | LLM invocation with streaming callback |
| `IF_ELSE` | `if-else` | Boolean branch, routes to next or fail nodes |
| `CONDITION_SWITCH` | `condition-switch` | Multi-branch conditional routing |

---

## DSL Format

Workflow definitions use JSON DSL with nodes and edges:

```json
{
  "flowId": "wf-001",
  "nodes": [
    { "id": "node-start::001", "data": { "nodeMeta": {...} } },
    { "id": "llm::002", "data": { "nodeMeta": {...}, "nodeParam": {...} } },
    { "id": "node-end::003", "data": { "nodeMeta": {...} } }
  ],
  "edges": [
    { "sourceNodeId": "node-start::001", "targetNodeId": "llm::002" },
    { "sourceNodeId": "llm::002", "targetNodeId": "node-end::003" }
  ]
}
```

**Branch routing**: edges with `sourceHandle` starting with `fail_` route to fail-branch nodes.

See `docs/dsl-example-linear.json` and `docs/dsl-example-branching.json` for complete examples.

---

## SSE Streaming

Execute a workflow and receive real-time streaming events:

```bash
curl -N http://localhost:7880/workflow/execute/stream \
  -H "Content-Type: application/json" \
  -d '{"flowId":"wf-001","appId":"app-001"}'
```

Event types: `workflow_start` → `node_start` → `node_process` → `node_end` → `workflow_end`

Interrupt events (`interrupt`) pause execution for human-in-the-loop scenarios.

---

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.8+
- MySQL 8.x

### Configure

Copy the example config and adjust database/LLM settings:

```bash
cp application-example.yml src/main/resources/application-local.yml
# Edit application-local.yml with your MySQL and LLM API credentials
```

### Database Setup

```bash
mysql -u root -p < src/main/resources/db/schema.sql
```

### Build & Run

```bash
mvn clean package -DskipTests
java -jar target/flowagent-engine.jar
# Health check
curl http://localhost:7880/actuator/health
```

---

## Project Structure

```
src/main/java/com/flowagent
├── FlowAgentApplication.java       # Spring Boot entry point
├── controller/                     # REST + SSE endpoints
│   ├── WorkflowController.java     # SSE streaming execution
│   ├── ProtocolController.java     # Workflow CRUD
│   └── vo/                         # Request/response VOs
├── engine/                         # Workflow engine core
│   ├── DagWorkflowEngine.java      # Sequential DFS executor
│   ├── ParallelWorkflowEngine.java # Parallel BFS executor
│   ├── WorkflowContextStore.java   # Instance-scoped variable pool
│   ├── core/                       # Engine abstraction layer
│   │   ├── WorkflowExecutionEngine.java   # Unified interface
│   │   ├── LegacyDagEngine.java           # LEGACY adapter
│   │   ├── LangGraphEngine.java           # LANGGRAPH adapter
│   │   ├── EngineFactory.java             # Factory + strategy routing
│   │   └── EngineConfiguration.java       # Spring config
│   ├── dag/                        # DAG kernel
│   │   ├── TopologyValidator.java  # Kahn cycle detection
│   │   ├── GraphBuilder.java       # Build pre/next/fail chains
│   │   └── GraphBuildResult.java   # Validation result
│   ├── dsl/                        # DSL parsing & rendering
│   │   ├── DslParser.java          # JSON → WorkflowDSL
│   │   ├── DslValidator.java       # Semantic validation
│   │   ├── VariablePathResolver.java # Dot-path resolution
│   │   └── VariableTemplateRender.java # {{var}} rendering
│   ├── node/                       # Node handler system
│   │   ├── WorkflowNodeHandler.java # Strategy interface
│   │   ├── AbstractNodeHandler.java # Template method (retry/timeout/error)
│   │   ├── FlowEventCallback.java   # Event callback interface
│   │   ├── impl/                    # Handler implementations
│   │   └── callback/               # SSE & message callbacks
│   ├── domain/                     # Domain models & callbacks
│   ├── integration/                # LLM integration layer
│   ├── context/                    # Engine context holder (TTL)
│   └── util/                       # Async & flow utilities
├── persistence/                    # MyBatis-Plus persistence
│   ├── entity/WorkflowEntity.java  # Flow table entity
│   ├── mapper/WorkflowMapper.java  # MyBatis mapper
│   └── service/WorkflowService.java # CRUD + DSL operations
├── common/                         # Cross-cutting concerns
│   ├── response/ApiResponse.java   # Unified API wrapper
│   ├── exception/                  # Global exception handling
│   ├── enums/                      # Engine status enums
│   └── id/                         # Snowflake ID generator
└── FlowAgentApplication.java       # Entry point with @MapperScan
```

---

## Error Strategy

Three error-handling strategies per node (configured via `retryConfig.errorStrategy`):

| Strategy | Behavior |
|---|---|
| `ERR_CODE` | Return custom error output, continue downstream |
| `ERR_CONDITION` | Route to fail-branch nodes (edges with `fail_` sourceHandle) |
| `ERR_INTERRUPT` | Abort entire workflow with error result |

---

## License

Private project — not yet open-sourced.
