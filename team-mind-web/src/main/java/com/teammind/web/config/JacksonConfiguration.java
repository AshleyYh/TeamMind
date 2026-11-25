package com.teammind.web.config;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON 序列化配置。
 *
 * <p>MyBatis-Plus 的 ASSIGN_ID 会生成 19 位雪花编号，超过 JavaScript 的
 * Number.MAX_SAFE_INTEGER（9007199254740991）。如果按 JSON 数字返回，浏览器和
 * Swagger UI 会四舍五入，导致拿到的 ID 与数据库不一致，后续按该 ID 查询或删除就会 404。
 * 因此统一把 Long/long 序列化为字符串；Jackson 反序列化时仍同时接受字符串和数字。</p>
 */
@Configuration
public class JacksonConfiguration {

    @Bean
    Module longToStringModule() {
        SimpleModule module = new SimpleModule("LongToStringModule");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return module;
    }
}
