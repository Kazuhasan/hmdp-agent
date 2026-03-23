package com.hmdp;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import javax.annotation.Resource;

@SpringBootTest
class RocketMQTest {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Test
    void testSend() {
        // 参数1: Topic (主题)
        // 参数2: 消息内容
        rocketMQTemplate.convertAndSend("test-topic", "Hello RocketMQ!");
        System.out.println("消息发送成功！");
    }
}