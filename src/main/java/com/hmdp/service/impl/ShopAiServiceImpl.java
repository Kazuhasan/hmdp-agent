package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopAiService;
import com.hmdp.utils.AiUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ShopAiServiceImpl implements IShopAiService {

    @Resource
    private AiUtils aiUtils;

    @Resource
    private IBlogService blogService; // 注入现有的 BlogService 用来查表

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 定义缓存key的前缀和过期时间
    private static final String AI_SUMMARY_CACHE_KEY = "cache:shop:ai:summary";
    private static final Long AI_SUMMARY_TTL_HOURS = 24L;

    @Override
    public Result getShopSummary(Long shopId) {
        // ==========================================
        // 步骤 1：查询 Redis 缓存 (Cache-Aside 核心起点)
        // ==========================================
        String cacheKey = AI_SUMMARY_CACHE_KEY + shopId;
        String cachedSummary = stringRedisTemplate.opsForValue().get(cacheKey);
        // 如果缓存命中，直接返回！这里耗时不到 20ms，彻底避开大模型的 3s 延迟！
        if (StrUtil.isNotBlank(cachedSummary)) {
            return Result.ok(cachedSummary);
        }
        // ==========================================
        // 步骤 2：缓存未命中，开始组装业务数据 (DB 兜底)
        // ==========================================

        // 1. 从数据库查询真实评价
        // 逻辑：查询该商户下、点赞数较高的前 10 条评论
        List<Blog> blogList = blogService.query()
                .eq("shop_id", shopId)
                .orderByDesc("liked") // 按点赞降序，取高质量评论
                .last("LIMIT 10")
                .list();

        // 2. 边界处理：如果没评论，直接返回
        if (CollUtil.isEmpty(blogList)) {
            return Result.ok("该商户暂无足够评论，AI 无法生成总结。");
        }

        // 3. 提取评论内容 (content) 并过滤掉空的
        List<String> reviewList = blogList.stream()
                .map(Blog::getContent)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(reviewList)) {
            return Result.ok("评论内容为空，无法生成。");
        }

        // 4. 拼接 Prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("商户ID：").append(shopId).append("\n");
        prompt.append("以下是顾客的真实评价：\n");
        for (String review : reviewList) {
            // 简单清洗：去掉过长的换行，控制长度
            String cleanReview = review.replace("\n", "，");
            // 截断过长评论防止 Token 超出（可选）
            if(cleanReview.length() > 200) cleanReview = cleanReview.substring(0, 200) + "...";
            prompt.append("- ").append(cleanReview).append("\n");
        }

        // 5. 调用 AI 工具类
        String summary = aiUtils.askAi(prompt.toString());

        // ==========================================
        // 步骤 4：将 AI 结果回写到 Redis，并设置过期时间
        // ==========================================
        if (StrUtil.isNotBlank(summary)) {
            // 设置 24 小时的 TTL。
            // 既保证了明天这个商户的数据能更新（最终一致性），又挡住了今天所有重复的请求。
            stringRedisTemplate.opsForValue().set(cacheKey, summary, AI_SUMMARY_TTL_HOURS, TimeUnit.HOURS);
        }

        // 6. 返回结果
        return Result.ok(summary);
    }
}