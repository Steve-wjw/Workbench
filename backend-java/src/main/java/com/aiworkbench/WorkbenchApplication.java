package com.aiworkbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java API 服务的 Spring Boot 启动入口。
 *
 * <p>该服务负责业务数据管理、统一 API 契约以及对 Python 服务的内部编排。
 */
@SpringBootApplication
public class WorkbenchApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WorkbenchApplication.class, args);
    }
}
