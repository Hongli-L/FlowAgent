package com.flowagent.persistence.service;

import com.flowagent.persistence.entity.NodeRunLogEntity;
import com.flowagent.persistence.entity.WorkflowExecutionEntity;
import com.flowagent.persistence.mapper.NodeRunLogMapper;
import com.flowagent.persistence.mapper.WorkflowExecutionMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validates execution-history persistence and the Redis real-time status cache.
 * Uses embedded Redis (real RedissonClient) plus Mockito stubs for the MyBatis mappers,
 * so no MySQL is required.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionHistoryServiceTest {

    private static RedisServer redisServer;
    private static RedissonClient redissonClient;

    @Mock
    private WorkflowExecutionMapper executionMapper;

    @Mock
    private NodeRunLogMapper nodeLogMapper;

    private ExecutionHistoryService service;

    @BeforeAll
    static void startRedis() throws Exception {
        redisServer = RedisServer.newRedisServer().port(16379).build();
        redisServer.start();
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:16379").setDatabase(0);
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        service = new ExecutionHistoryService(executionMapper, nodeLogMapper, redissonClient);
    }

    @Test
    void createExecutionWritesRedisStatusAndCompleteDeletesIt() {
        Long executionId = service.createExecution("wf-redis-1", "API");
        assertNotNull(executionId);

        // Real-time status reflects RUNNING from Redis
        assertEquals("RUNNING", service.getLiveStatus(executionId));

        // Simulate MySQL row for completion + later fallback read
        WorkflowExecutionEntity entity = new WorkflowExecutionEntity();
        entity.setExecutionId(executionId);
        entity.setWorkflowId("wf-redis-1");
        entity.setStatus("RUNNING");
        entity.setStartTime(LocalDateTime.now().minusSeconds(1));
        when(executionMapper.selectById(executionId)).thenReturn(entity);

        service.completeExecution(executionId, "SUCCESS");

        // Cache cleared on completion -> falls back to MySQL (now SUCCESS)
        assertEquals("SUCCESS", service.getLiveStatus(executionId));
        verify(executionMapper).updateById(any(WorkflowExecutionEntity.class));
    }

    @Test
    void recordNodeLogAssignsSnowflakeIdAndPersists() {
        NodeRunLogEntity log = new NodeRunLogEntity();
        log.setExecutionId(111L);
        log.setNodeId("llm::001");
        log.setNodeType("LLM");
        log.setStatus("SUCCESS");

        service.recordNodeLog(log);

        assertNotNull(log.getLogId());
        verify(nodeLogMapper).insert(any(NodeRunLogEntity.class));
    }

    @Test
    void liveStatusReturnsNullWhenUnknown() {
        assertNull(service.getLiveStatus(999999999L));
    }
}
