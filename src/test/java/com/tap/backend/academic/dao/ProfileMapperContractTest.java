package com.tap.backend.academic.dao;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 校验 ProfileMapper.xml 中仍在使用的查询（班级名册 getAllStudents）走统一 class_member 名册，
 * 而不是遗留的 student.class_name 模糊匹配。班级画像的提交统计已迁移到 student_problem_attempt，
 * 不再经过 submit_situation，因此这里只校验名册来源。
 */
class ProfileMapperContractTest {

    @Test
    void classRosterQueryUsesCanonicalClassMembership() throws IOException {
        try (var stream = getClass().getResourceAsStream("/mappers/ProfileMapper.xml")) {
            assertTrue(stream != null, "ProfileMapper.xml should be on the classpath");
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            // getAllStudents 的 classId 分支必须基于 class_member + student_profile
            assertTrue(xml.contains("FROM class_member cm"), "名册查询应基于 class_member");
            assertTrue(xml.contains("cm.class_id = #{classId}"), "名册查询应按 classId 过滤");
            assertTrue(xml.contains("JOIN student_profile sp ON sp.id = cm.student_id"),
                    "名册查询应 join student_profile");
            assertTrue(xml.contains("cm.member_status = 'ACTIVE'"), "名册查询应限定 ACTIVE 成员");

            // 班级画像不再读取遗留 submit_situation 做提交统计
            assertFalse(xml.contains("getClassExperimentStats"),
                    "遗留 submit_situation 班级统计 getClassExperimentStats 已废弃，应已移除");
        }
    }
}
