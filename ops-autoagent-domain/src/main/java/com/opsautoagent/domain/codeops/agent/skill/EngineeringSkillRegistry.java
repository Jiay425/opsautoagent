package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EngineeringSkillRegistry {

    private final Map<String, EngineeringSkill> skillMap;

    public EngineeringSkillRegistry(List<EngineeringSkill> skills) {
        this.skillMap = new LinkedHashMap<>();
        skills.stream()
                .sorted(Comparator.comparing(skill -> skill.metadata().getSkillId()))
                .forEach(skill -> this.skillMap.put(skill.metadata().getSkillId(), skill));
    }

    public Optional<EngineeringSkill> find(String skillId) {
        return Optional.ofNullable(skillMap.get(skillId));
    }

    public List<EngineeringSkillEntity> listMetadata() {
        return skillMap.values().stream()
                .map(EngineeringSkill::metadata)
                .toList();
    }

}
