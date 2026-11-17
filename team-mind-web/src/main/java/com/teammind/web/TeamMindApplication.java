package com.teammind.web;

import com.teammind.ai.config.QwenProperties;
import com.teammind.knowledge.config.DocumentProperties;
import com.teammind.knowledge.config.QdrantKnowledgeProperties;
import com.teammind.web.config.QdrantProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.teammind")
@MapperScan("com.teammind.**.mapper")
@EnableConfigurationProperties({QdrantProperties.class, DocumentProperties.class,
        QdrantKnowledgeProperties.class, QwenProperties.class})
public class TeamMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeamMindApplication.class, args);
    }
}
