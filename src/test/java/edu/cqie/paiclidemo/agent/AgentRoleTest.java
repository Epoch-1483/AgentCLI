package edu.cqie.paiclidemo.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentRole 枚举测试。
 */
class AgentRoleTest {

    @Test
    @DisplayName("三个角色枚举值都存在")
    void threeRolesExist() {
        AgentRole[] roles = AgentRole.values();
        assertEquals(3, roles.length);
    }

    @Test
    @DisplayName("PLANNER 角色属性正确")
    void plannerRole() {
        AgentRole role = AgentRole.PLANNER;
        assertEquals("规划者", role.getDisplayName());
        assertNotNull(role.getDescription());
        assertFalse(role.getDescription().isEmpty());
    }

    @Test
    @DisplayName("WORKER 角色属性正确")
    void workerRole() {
        AgentRole role = AgentRole.WORKER;
        assertEquals("执行者", role.getDisplayName());
        assertNotNull(role.getDescription());
        assertFalse(role.getDescription().isEmpty());
    }

    @Test
    @DisplayName("REVIEWER 角色属性正确")
    void reviewerRole() {
        AgentRole role = AgentRole.REVIEWER;
        assertEquals("检查者", role.getDisplayName());
        assertNotNull(role.getDescription());
        assertFalse(role.getDescription().isEmpty());
    }

    @Test
    @DisplayName("valueOf 能正确反序列化")
    void valueOfWorks() {
        assertEquals(AgentRole.PLANNER, AgentRole.valueOf("PLANNER"));
        assertEquals(AgentRole.WORKER, AgentRole.valueOf("WORKER"));
        assertEquals(AgentRole.REVIEWER, AgentRole.valueOf("REVIEWER"));
    }
}
