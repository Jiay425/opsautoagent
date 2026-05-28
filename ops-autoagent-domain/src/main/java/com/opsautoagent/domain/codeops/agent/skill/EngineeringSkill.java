package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;

public interface EngineeringSkill {

    EngineeringSkillEntity metadata();

    EngineeringSkillResultEntity execute(EngineeringTaskEntity task);

}
