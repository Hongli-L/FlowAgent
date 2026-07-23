package com.flowagent.persistence.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.common.id.IdUtil;
import com.flowagent.engine.dag.TopologyValidator;
import com.flowagent.engine.dsl.DslParser;
import com.flowagent.engine.dsl.DslValidator;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.persistence.entity.WorkflowEntity;
import com.flowagent.persistence.mapper.WorkflowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final DslParser dslParser;
    private final DslValidator dslValidator;
    private final TopologyValidator topologyValidator;

    public WorkflowService(WorkflowMapper workflowMapper,
                           DslParser dslParser,
                           DslValidator dslValidator,
                           TopologyValidator topologyValidator) {
        this.workflowMapper = workflowMapper;
        this.dslParser = dslParser;
        this.dslValidator = dslValidator;
        this.topologyValidator = topologyValidator;
    }

    public WorkflowDSL getWorkflowDSL(String workflowId) {
        WorkflowEntity entity = getWorkflow(workflowId);
        WorkflowDSL dsl = dslParser.parseFromStoredData(entity.getData());
        dsl.setFlowId(workflowId);
        if (log.isDebugEnabled()) {
            log.debug("Loaded workflow: id={}, nodes={}, edges={}", workflowId, dsl.getNodes().size(), dsl.getEdges().size());
        }
        return dsl;
    }

    public WorkflowDSL validateWorkflow(Map<String, Object> data) {
        WorkflowDSL dsl = dslParser.parseFromEnvelope(data);
        dslValidator.validate(dsl);
        topologyValidator.validate(dsl);
        return dsl;
    }

    public WorkflowEntity saveWorkflow(Map<String, Object> data) {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(IdUtil.genId());
        entity.setGroupId(IdUtil.genId());
        entity.setData(data != null ? JSON.toJSONString(data) : "{}");
        entity.setCreateAt(LocalDateTime.now());
        entity.setUpdateAt(LocalDateTime.now());
        workflowMapper.insert(entity);
        return entity;
    }

    public WorkflowEntity getWorkflow(String flowId) {
        try {
            LambdaQueryWrapper<WorkflowEntity> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(WorkflowEntity::getId, Long.parseLong(flowId));
            WorkflowEntity entity = workflowMapper.selectOne(queryWrapper);
            if (entity == null) {
                throw new NodeCustomException(ErrorCode.FLOW_GET_ERROR, "Workflow not found: " + flowId);
            }
            return entity;
        } catch (NumberFormatException e) {
            throw new NodeCustomException(ErrorCode.FLOW_GET_ERROR, "Invalid flow ID format: " + flowId);
        }
    }

    public void updateWorkflow(String flowId, Map<String, Object> data) {
        try {
            LambdaUpdateWrapper<WorkflowEntity> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(WorkflowEntity::getId, Long.parseLong(flowId));
            if (data != null) {
                updateWrapper.set(WorkflowEntity::getData, JSON.toJSONString(data));
            }
            updateWrapper.set(WorkflowEntity::getUpdateAt, LocalDateTime.now());
            workflowMapper.update(null, updateWrapper);
        } catch (NumberFormatException e) {
            throw new NodeCustomException(ErrorCode.FLOW_GET_ERROR, "Invalid flow ID format: " + flowId);
        }
    }

    public void deleteWorkflow(String flowId) {
        try {
            LambdaQueryWrapper<WorkflowEntity> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(WorkflowEntity::getId, Long.parseLong(flowId));
            workflowMapper.delete(queryWrapper);
        } catch (NumberFormatException e) {
            throw new NodeCustomException(ErrorCode.FLOW_GET_ERROR, "Invalid flow ID format: " + flowId);
        }
    }
}
