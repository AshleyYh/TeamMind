package com.teammind.web.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 本地开发数据初始化。仅在 local profile 下创建无登录能力的占位用户，
 * 用于满足知识库所有者外键；该账号没有可用密码，不能作为生产账号或认证方案。
 */
@Component
@Profile("local")
public class LocalDevelopmentDataInitializer implements ApplicationRunner {
    public static final long LOCAL_USER_ID = 1L;

    private final JdbcTemplate jdbcTemplate;

    public LocalDevelopmentDataInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, nickname, status, deleted, created_at, updated_at)
                VALUES (?, ?, '!', ?, 1, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """, LOCAL_USER_ID, "local-developer", "本地开发用户");
    }
}
