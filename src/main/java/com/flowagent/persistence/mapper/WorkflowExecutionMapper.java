package com.flowagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowagent.persistence.entity.WorkflowExecutionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Workflow execution mapper.
 */
@Mapper
public interface WorkflowExecutionMapper extends BaseMapper<WorkflowExecutionEntity> {

    @Select("SELECT * FROM workflow_execution WHERE workflow_id = #{workflowId} " +
            "ORDER BY start_time DESC LIMIT #{size} OFFSET #{offset}")
    List<WorkflowExecutionEntity> selectByWorkflowId(@Param("workflowId") String workflowId,
                                                    @Param("offset") int offset,
                                                    @Param("size") int size);

    @Select("SELECT COUNT(*) FROM workflow_execution WHERE workflow_id = #{workflowId}")
    long countByWorkflowId(@Param("workflowId") String workflowId);
}
