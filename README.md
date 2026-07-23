# FlowAgent

Distributed AI Agent workflow orchestration platform — a backend service built around a custom DAG scheduling engine.

Supports dynamic LLM node and flow logic orchestration via JSON DSL, with DAG topology scheduling, circular dependency detection, parallel node scheduling, cross-node state context management, and SSE streaming execution output.

## Tech Stack

- Java 21, Spring Boot 3.5
- MyBatis-Plus + MySQL
- Spring AI (LLM integration)
- OkHttp, Fastjson2, Hutool, Guava, TransmittableThreadLocal

## Prerequisites

- JDK 21
- Maven 3.8+
- MySQL 8.x (local or container)

## Build & Run

```bash
mvn clean package -DskipTests
java -jar target/flowagent-engine.jar
# Health check
curl http://localhost:7880/actuator/health
```

## Module Structure

```
src/main/java/com/flowagent
├── FlowAgentApplication.java   # Entry point
├── controller/                 # REST API (with SSE streaming)
├── engine/                     # Workflow engine core
│   ├── domain/                 # DSL models (Node / Edge / NodeData etc.)
│   ├── node/                   # Node handlers (Start / End / LLM etc.)
│   ├── context/                # Engine context
│   ├── integration/            # LLM integration
│   └── util/                   # Engine utilities
├── flow/                       # Workflow metadata persistence
├── exception/                  # Exceptions & error codes
└── components/                 # Common components (ID / utilities)
```

> Note: The project is undergoing layered refactoring and capability enhancement; module structure will evolve with subsequent iterations.
