package com.hmdp.listener;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Slf4j
@Component
// topic: 必须和生产者发送的 Topic 一致 ("seckill_topic")
// consumerGroup: 消费者组名，必须和配置文件里不同，或者在 RocketMQ 里唯一
@RocketMQMessageListener(topic = "seckill_topic", consumerGroup = "seckill-consumer-group")
public class SeckillListener implements RocketMQListener<VoucherOrder> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(VoucherOrder voucherOrder) {
        log.info("消费者接收到消息，开始处理订单。用户ID：{}，订单ID：{}", voucherOrder.getUserId(), voucherOrder.getId());

        try {
            // 调用 Service 层的业务逻辑
            voucherOrderService.handleVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("处理订单失败", e);
            // 如果抛出异常，RocketMQ 会自动重试（默认 16 次），确保消息不丢失
            throw new RuntimeException(e);
        }
    }
}