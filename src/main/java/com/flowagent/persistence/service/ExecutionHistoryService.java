package com.flowagent.persistence.service;

import com.alibaba.fastjson2.JSON;
import com.flowagent.common.enums.ExecutionStatusEnum;
import com.flowagent.common.util.SnowflakeIdGenerator;
import com.flowagent.persistence.entity.NodeRunLogEntity;
import com.flowagent.persistence.entity.WorkflowExecutionEntity;
import com.flowagent.persistence.mapper.NodeRunLogMapper;
import com.flowagent.persistence.mapper.WorkflowExecutionMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Records and queries workflow execution history.
 *
 * <p>Design: a workflow run starts with a {@code workflow_execution} row in RUNNING state
 * and a real-time status copy in Redis (key {@code execution:status:{executionId}}, TTL 10min).
 * Each node activation appends a {@code node_run_log} row. On completion the Redis copy is
 * deleted and the final status is persisted to MySQL, so the cache only carries in-flight
 * status and never goes stale.</p>
 */
@Slf4j
@Service
public class ExecutionHistoryService {

    private static final String STATUS_KEY_PREFIX = "execution:status:";
    private static final long STATUS_TTL_MINUTES = 10;

    private final WorkflowExecutionMapper executionMapper;
    private final NodeRunLogMapper nodeLogMapper;
    private final RedissonClient redissonClient;

    public ExecutionHistoryService(WorkflowExecutionMapper executionMapper,
                                   NodeRunLogMapper nodeLogMapper,
                                   RedissonClient redissonClient) {
        this.executionMapper = executionMapper;
        this.nodeLogMapper = nodeLogMapper;
        this.redissonClient = redissonClient;
    }

    /**
     * Create a RUNNING execution instance and publish its status to Redis.
     *
     * @param workflowId    workflow id from the DSL
     * @param triggerSource  e.g. API / SCHEDULER
     * @return generated execution id
     */
    public Long createExecution(String workflowId, String triggerSource) {
        Long executionId = SnowflakeIdGenerator.nextId();
        return createExecution(workflowId, executionId, triggerSource);
    }

    /**
     * Create a RUNNING execution with a caller-supplied id (used by the engine so the
     * id can be shared with the per-node tracer via the execution context).
     */
    public Long createExecution(String workflowId, Long executionId, String triggerSource) {
        WorkflowExecutionEntity entity = new WorkflowExecutionEntity();
        entity.setExecutionId(executionId);
        entity.setWorkflowId(workflowId);
        entity.setStatus(ExecutionStatusEnum.RUNNING.name());
        entity.setTriggerSource(triggerSource);
        LocalDateTime now = LocalDateTime.now();
        entity.setStartTime(now);
        entity.setCreateAt(now);
        entity.setUpdateAt(now);
        executionMapper.insert(entity);
        writeStatusCache(executionId, ExecutionStatusEnum.RUNNING.name(), workflowId);
        return executionId;
    }

    /**
     * Mark an execution finished: persist final status to MySQL and drop the Redis copy.
     */
    public void completeExecution(Long executionId, String status) {
        WorkflowExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity == null) {
            log.warn("Execution {} not found when completing, status={}", executionId, status);
            return;
        }
        LocalDateTime end = LocalDateTime.now();
        entity.setStatus(status);
        entity.setEndTime(end);
        if (entity.getStartTime() != null) {
            entity.setDurationMs(Duration.between(entity.getStartTime(), end).toMillis());
        }
        entity.setUpdateAt(end);
        executionMapper.updateById(entity);
        redissonClient.getBucket(STATUS_KEY_PREFIX + executionId).delete();
    }

    /**
     * Persist one node run log. Assigns a snowflake id if absent.
     */
    public void recordNodeLog(NodeRunLogEntity log) {
        if (log.getLogId() == null) {
            log.setLogId(SnowflakeIdGenerator.nextId());
        }
        if (log.getCreateAt() == null) {
            log.setCreateAt(LocalDateTime.now());
        }
        nodeLogMapper.insert(log);
    }

    public List<WorkflowExecutionEntity> listExecutions(String workflowId, int page, int size) {
        int offset = (Math.max(page, 1) - 1) * Math.max(size, 1);
        return executionMapper.selectByWorkflowId(workflowId, offset, Math.max(size, 1));
    }

    public long countExecutions(String workflowId) {
        return executionMapper.countByWorkflowId(workflowId);
    }

    public WorkflowExecutionEntity getExecution(Long executionId) {
        return executionMapper.selectById(executionId);
    }

    public List<NodeRunLogEntity> getNodeLogs(Long executionId) {
        return nodeLogMapper.selectByExecutionId(executionId);
    }

    /**
     * Real-time status: prefer the Redis copy (reflects in-flight state within milliseconds),
     * fall back to MySQL once the cache is cleared on completion.
     */
    public String getLiveStatus(Long executionId) {
        RBucket<String> bucket = redissonClient.getBucket(STATUS_KEY_PREFIX + executionId);
        String cached = bucket.get();
        if (cached != null) {
            return parseStatus(cached);
        }
        WorkflowExecutionEntity entity = executionMapper.selectById(executionId);
        return entity == null ? null : entity.getStatus();
    }

    private void writeStatusCache(Long executionId, String status, String workflowId) {
        Map<String, Object> payload = new HashMap<>(3);
        payload.put("status", status);
        payload.put("workflowId", workflowId);
        payload.put("timestamp", System.currentTimeMillis());
        redissonClient.getBucket(STATUS_KEY_PREFIX + executionId)
                .set(JSON.toJSONString(payload), STATUS_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private String parseStatus(String json) {
        try {
            Map<?, ?> map = JSON.parseObject(json, Map.class);
            return map == null ? null : (String) map.get("status");
        } catch (Exception e) {
            log.warn("Failed to parse execution status cache: {}", json, e);
            return null;
        }
    }
}
