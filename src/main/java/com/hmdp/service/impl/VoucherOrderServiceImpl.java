package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /*@PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }*/

    /*private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        // 2.1如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 5.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        // 2.1如果获取失败，说明pending-list中没有消息，结束循环
                        break;
                    }
                    // 3.解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 5.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }*/

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderService;

    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象(兜底方案：虽然 Redis 脚本判断了一次，但为了防止 MQ 重复消费导致的数据不一致，这里建议还是加锁)
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返货错误或重试
            log.error("不允许重复下单");
            return;
        }
        try{
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally{
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //  获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            // 2.1 不为0，代表没有购买资格
            return Result.fail((r == 1 ? "库存不足" : "不能重复下单"));
        }
        // ============================================================
        // 🚀 【关键修改点】发送消息到 RocketMQ
        // ============================================================

        // 5.1 准备消息体 (VoucherOrder 对象)
        // 我们只需要传最核心的数据给消费者：订单ID、用户ID、代金券ID
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 5.2 发送消息
        // 参数 "seckill_topic"：这是 Topic 的名字，你可以随便起，但要记住它，一会儿写消费者要用。
        // 因为我们启动 Broker 时加了 autoCreateTopicEnable=true，所以这里直接发，MQ 会自动创建这个 Topic。
        try {
            log.info("开始发送秒杀消息，用户ID：{}", userId);
            rocketMQTemplate.convertAndSend("seckill_topic", voucherOrder);
            log.info("消息发送成功！");
        } catch (Exception e) {
            log.error("RocketMQ 消息发送失败，订单ID：{}", orderId, e);
            // 如果消息发不出去，说明 MQ 挂了或者网络不通
            // 此时 Redis 库存已经扣了，为了数据一致性，理论上这里应该回滚 Redis (先把这放一边，先跑通主流程)
            return Result.fail("系统繁忙，请稍后重试");
        }
        // 6.返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //  获取用户
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            // 2.1 不为0，代表没有购买资格
            return Result.fail((r == 1 ? "库存不足" : "不能重复下单"));
        }
        // 2.2 为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4 用户id
        voucherOrder.setUserId(userId);
        // 2.5 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6 放入阻塞队列
        orderTasks.add(voucherOrder);

        // 3.获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();
        // 5.1 查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2 判断是否存在
        if(count > 0){
            //用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update() //乐观锁解决超卖问题CAS法
                .setSql("stock = stock - 1") //set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock >0
                .update();
        if (!success){
            //扣减失败
            log.error("库存不足!");
            return;
        }
        // 7.创建订单
        save(voucherOrder);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始!");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            //尚未开始
            return Result.fail(("秒杀已经结束！"));
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1){
            //库存不足
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返货错误或重试
            return Result.fail("不允许重复下单");

        }
        try{
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally{
            lock.unlock();
        }
    }*/

    /*@Transactional
    public Result createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1 查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        // 5.2 判断是否存在
        if(count > 0){
            //用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update() //乐观锁解决超卖问题CAS法
                .setSql("stock = stock - 1") //set stock = stock - 1
                .eq("voucher_id", voucherOrder).gt("stock", 0) // where id = ? and stock >0
                .update();
        if (!success){
            //扣减失败
            return Result.fail("库存不足!");
        }
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2 用户id
        voucherOrder.setUserId(userId);
        // 7.3 代金券id
        voucherOrder.setVoucherId(voucherOrder);
        save(voucherOrder);
        // 8.返回订单id
        return Result.ok(orderId);
    }*/
}