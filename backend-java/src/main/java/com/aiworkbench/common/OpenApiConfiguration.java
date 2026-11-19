package com.aiworkbench.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Java API 的 OpenAPI 基础描述。
 *
 * <p>Swagger UI 仅展示本地 API 契约和非敏感配置，不包含数据库账号、API Key
 * 或模型服务商密钥。
 */
@Configuration
public class OpenApiConfiguration {

    /**
     * 配置接口标题、版本、默认本地服务地址和请求追踪头。
     *
     * @return 可供 springdoc 生成接口文档的 OpenAPI 配置
     */
    @Bean
    OpenAPI workbenchOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Personal AI Workbench API")
                        .version("1.0.0")
                        .description("V1 本地个人工作台 API：资料、面经、分类、AI 热点及 GitHub 项目。业务数据保存在本机；生成面经和外部内容同步会访问对应服务。"))
                .addServersItem(new Server().url("http://localhost:8080").description("本地 Java API"))
                .components(new Components().addParameters(
                        "RequestId",
                        new Parameter()
                                .in("header")
                                .name(RequestIdFilter.REQUEST_ID_HEADER)
                                .description("可选的调用链请求标识；未传入时服务会自动生成。")
                                .schema(new StringSchema())));
    }
}
