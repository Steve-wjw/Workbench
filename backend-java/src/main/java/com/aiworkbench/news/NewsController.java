package com.aiworkbench.news;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/**
 * AI 热点临时查询接口。
 */
@RestController
@RequestMapping("/api/v1/ai-news")
@Tag(name = "AI 热点", description = "查询当天和前一天的 AI 新闻概述。")
public class NewsController {

    /** AI 热点业务服务。 */
    private final NewsService service;

    /**
     * 创建热点控制器。
     *
     * @param service AI 热点业务服务
     */
    public NewsController(NewsService service) {
        this.service = service;
    }

    /**
     * 查询当天和前一天的 AI 热点。
     *
     * @param refresh 是否跳过短时内存缓存
     * @return 聚合后的热点数据
     */
    @GetMapping
    @Operation(summary = "查询 AI 热点")
    public ResponseEntity<NewsModels.NewsResponse> list(
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(refresh));
    }
}
