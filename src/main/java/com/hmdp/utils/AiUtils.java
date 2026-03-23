package com.hmdp.utils;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AiUtils {

    // 替换为你自己的 DeepSeek API Key
    private static final String API_KEY = "";
    // DeepSeek 的接口地址
    private static final String API_URL = "";

    /**
     * 调用 AI 生成总结
     * @param content 提示词（包含评论数据）
     * @return AI 的回答
     */
    public String askAi(String content) {
        // 1. 构建请求体 (OpenAI 官方标准格式)
        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat"); // 使用的模型
        body.put("temperature", 0.7);       // 创意程度

        // 构建消息列表
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        // 构建系统预设（让 AI 扮演点评专家）
        Map<String, String> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", "你是一个资深的点评达人。请根据用户提供的多条餐厅评论，" +
                "简明扼要地总结出这家店的：1.【必吃菜品】 2.【服务槽点】 3.【适合场景】。" +
                "字数控制在100字以内，风格犀利幽默。");

        body.put("messages", new Object[]{systemMessage, userMessage});

        // 2. 发送 POST 请求
        try {
            String jsonBody = JSONUtil.toJsonStr(body);
            HttpResponse response = HttpRequest.post(API_URL)
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json")
                    .body(jsonBody)
                    .timeout(20000) // AI 响应较慢，超时设长一点
                    .execute();

            String result = response.body();

            // 3. 解析响应 (提取 content)
            JSONObject json = JSONUtil.parseObj(result);
            // 这是一个深层嵌套：choices[0].message.content
            JSONArray choices = json.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject firstChoice = choices.getJSONObject(0);
                JSONObject message = firstChoice.getJSONObject("message");
                return message.getStr("content");
            }
            return "AI 开小差了，没能生成总结...";

        } catch (Exception e) {
            log.error("调用 AI 接口失败", e);
            return "AI 服务繁忙，请稍后再试。";
        }
    }
}
