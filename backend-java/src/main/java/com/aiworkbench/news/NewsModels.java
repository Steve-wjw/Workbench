package com.aiworkbench.news;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI 热点接口的响应模型。
 */
public final class NewsModels {

    /** 工具类不允许实例化。 */
    private NewsModels() {
    }

    /**
     * 按今天和昨天聚合的热点响应。
     *
     * @param today 今天的新闻
     * @param yesterday 昨天的新闻
     * @param fetchedAt 本次获取时间
     * @param cached 是否来自短时内存缓存
     * @param warning 数据更新异常或截断提示
     */
    public record NewsResponse(
            NewsDay today,
            NewsDay yesterday,
            OffsetDateTime fetchedAt,
            boolean cached,
            String warning) {
    }

    /**
     * 某个业务日期的新闻集合。
     *
     * @param date 业务日期
     * @param label 页面展示标签
     * @param items 新闻列表
     */
    public record NewsDay(
            LocalDate date,
            String label,
            List<NewsItem> items) {
    }

    /**
     * 单条临时新闻。
     *
     * @param id 新闻源提供的临时 ID
     * @param title 新闻标题
     * @param source 新闻来源
     * @param url 原文地址
     * @param publishedAt 发布时间
     * @param summary AIHOT 提供的完整概述
     * @param category 简单主题分类
     * @param providerUrl AIHOT 详情地址
     * @param reason AIHOT 推荐理由
     * @param hotScore 上游评分；未提供时为空
     */
    public record NewsItem(
            String id,
            String title,
            String source,
            String url,
            OffsetDateTime publishedAt,
            String summary,
            String category,
            String providerUrl,
            String reason,
            Double hotScore) {
    }
}
