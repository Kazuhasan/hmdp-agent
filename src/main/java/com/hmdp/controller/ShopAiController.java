package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.springframework.web.bind.annotation.*;
import com.hmdp.service.IShopAiService;
import javax.annotation.Resource;

@RestController
@RequestMapping("/shop-ai")
public class ShopAiController {

    @Resource
    private IShopAiService shopAiService;

    @GetMapping("/summary/{shopId}")
    public Result getShopSummary(@PathVariable("shopId") Long shopId) {
        // Controller 只负责接收参数，直接转发给 Service
        return shopAiService.getShopSummary(shopId);
    }
}