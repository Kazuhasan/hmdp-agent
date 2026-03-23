package com.hmdp.service;

import com.hmdp.dto.Result;

public interface IShopAiService {
    /**
     * 获取商户的 AI 评价总结
     * @param shopId 商户ID
     * @return 总结结果
     */
    Result getShopSummary(Long shopId);
}