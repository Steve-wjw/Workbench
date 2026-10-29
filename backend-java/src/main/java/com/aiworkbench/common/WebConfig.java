package com.aiworkbench.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层基础配置。
 *
 * <p>当前主要配置本地前端开发所需的 CORS 规则以及 HTTP 客户端构造器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 配置本地前端访问 Java API 的跨域规则。
     *
     * @param registry Spring MVC CORS 注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    /**
     * 创建统一的 RestClient 构造器。
     *
     * @return RestClient 构造器
     */
    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
