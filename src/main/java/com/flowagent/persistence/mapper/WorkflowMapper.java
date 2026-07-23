package com.flowagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowagent.persistence.entity.WorkflowEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Workflow mapper
 */
@Mapper
public interface WorkflowMapper extends BaseMapper<WorkflowEntity> {
}
