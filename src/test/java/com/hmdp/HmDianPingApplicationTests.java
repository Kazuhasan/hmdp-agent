package com.hmdp;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.service.impl.VoucherServiceImpl;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import  static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

// 【核心注解】
// @SpringBootTest 的作用是：告诉 IDEA，运行这个测试时，先把 Spring 容器启动起来。
// 只有容器启动了，我们才能用 @Resource 注入 Service，否则 shopService 是 null。
@SpringBootTest
class HmDianPingApplicationTests {

    // 注入你要测试的 Service
    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private VoucherServiceImpl voucherService;

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void init_method(){
        testSaveShop();
        testAddSeckillVoucher();
    }

    @Test
        //逻辑过期预热店铺1
    void testSaveShop() {
        // 这里的 1L 是店铺ID，20L 是逻辑过期时间（秒）
        // 先设个短的 10秒 或 20秒，方便你测试过期后的重建效果
        shopService.saveShop2Redis(1L, 10L);
        shopService.saveShop2Redis(2L, 10L);
        shopService.saveShop2Redis(3L, 10L);
        shopService.saveShop2Redis(5L, 10L);
        shopService.saveShop2Redis(11L, 10L);
        shopService.saveShop2Redis(12L, 10L);
        shopService.saveShop2Redis(13L, 10L);
        System.out.println("数据预热成功！");
    }

    @Test //添加秒杀券，店铺1
    void testAddSeckillVoucher() {
        Voucher voucher = new Voucher();
        // 1. 关联商铺ID (假设是 ID 为 1 的商铺)
        voucher.setShopId(1L);
        // 2. 基本信息
        voucher.setTitle("100元代金券");
        voucher.setSubTitle("周一至周五可用");
        voucher.setPayValue(8000L); // 支付金额 (单位是分，80元)
        voucher.setActualValue(10000L); // 抵扣金额 (单位是分，100元)
        voucher.setType(1); // 0:普通券, 1:秒杀券 (这里必须是1)
        voucher.setStatus(1); // 1:上架, 2:下架

        // 3. 秒杀专用信息 (对应 tb_voucher_seckill 表)
        voucher.setStock(100); // 库存

        // 设置开始时间: 现在开始
        voucher.setBeginTime(LocalDateTime.now());
        // 设置结束时间: 3天后结束
        voucher.setEndTime(LocalDateTime.now().plusDays(3));

        // 4. 调用 Service 添加秒杀券
        // 这行代码会自动向 tb_voucher 和 tb_voucher_seckill 两张表插入数据
        voucherService.addSeckillVoucher(voucher);

        System.out.println("秒杀券添加成功！ID为：" + voucher.getId());
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testLogicalExpire() throws InterruptedException {
        Shop shop = shopService.getById(1L);

        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }


    @Test
    void testSeckillConcurrency() throws InterruptedException {
        // 1. 准备参数
        Long voucherId = 10L; // 你的秒杀券ID
        int threadCount = 200; // 模拟 200 个用户同时抢

        // 2. 准备线程池 (模拟大军)
        ExecutorService es = Executors.newFixedThreadPool(threadCount);

        // 3. 准备倒计时锁 (发令枪，保证主线程等待所有子线程跑完)
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 4. 开始轰炸
        for (int i = 0; i < threadCount; i++) {
            // 这里的 userId 用循环变量 i 生成，模拟不同的用户
            //long userId = i + 1000;
            long userId = 1L;

            es.submit(() -> {

                    // === 关键步骤：模拟登录状态 ===
                    // 因为没有经过拦截器，Service 里的 UserHolder.getUser() 是空的
                    // 所以我们在这里手动塞一个虚拟用户进去
                    UserDTO user = new UserDTO();
                    user.setId(userId);
                    UserHolder.saveUser(user);
                    // ===========================


                try{// 执行秒杀逻辑
                    voucherOrderService.seckillVoucher(voucherId);

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 记得清理 ThreadLocal，虽然测试结束进程就销毁了，但这是好习惯
                    UserHolder.removeUser();
                    // 每一个线程跑完，倒计时减一
                    latch.countDown();
                }
            });
        }

        // 5. 主线程等待，直到所有子线程执行完毕
        latch.await();
        es.shutdown();

        System.out.println("200个并发线程执行完毕！请去数据库查看结果。");
    }

    @Test
    void testDistributedLock() throws InterruptedException {
        // 1. 准备参数
        Long voucherId = 10L; // 确保数据库里有这个秒杀券
        int threadCount = 100; // 模拟100个并发请求

        // 2. 必须先登录，拿到 Authorization (Token)
        // 我们可以先模拟一个用户登录，获取他的 Token
        String token = loginAndGetToken("13612345678"); // 假设这是你的测试用户手机号

        ExecutorService es = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        System.out.println("开始向 Nginx (8080) 发送请求，将被分发到 8081 和 8082...");

        for (int i = 0; i < threadCount; i++) {
            es.submit(() -> {
                try {
                    // 3. 发送 HTTP 请求 (这是关键！不是调 Service，是发网络请求)
                    // 请求地址是 Nginx 的地址 8080
                    HttpResponse response = HttpRequest.post("http://localhost:8080/api/voucher-order/seckill/" + voucherId)
                            .header("authorization", token)
                            // ⬇️⬇️⬇️ 加上这一行核心代码 ⬇️⬇️⬇️
                            .header("Connection", "close")
                            // ⬆️⬆️⬆️ 告诉 Nginx：处理完这个请求就把 TCP 连断了，别复用！
                            .execute();

                    System.out.println("响应状态: " + response.getStatus() + " | 结果: " + response.body());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        es.shutdown();
        System.out.println("测试结束！请检查数据库是否只有 1 个订单。");
    }

    // 辅助方法：模拟登录获取 Token
    private String loginAndGetToken(String phone) {
        // 构造登录表单
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(phone);
        loginForm.setCode("123456"); // 假设你的验证码逻辑是校验Redis，或者你临时写死了
        // 这里为了方便，你也可以直接去浏览器登录一次，把 F12 里的 token 复制过来直接 return 字符串
        // return "这里填你浏览器里复制出来的真实Token";

        // 如果要代码自动登录：
        HttpResponse response = HttpRequest.post("http://localhost:8080/login")
                .body(JSONUtil.toJsonStr(loginForm))
                .execute();

        // 解析返回的 JSON 拿到 token (这里假设返回结构是 standard 的 Result)
        // 简单粗暴点，建议你直接在浏览器登录，复制 token 填在这个方法里返回即可
        return "194f8280f8e5490aa56f7437ed6a88dc"; // <--- 替换为你真实的 token
    }

    @Test
    void testOneRequest() {
        String token = "194f8280f8e5490aa56f7437ed6a88dc";
        HttpResponse response = HttpRequest.post("http://localhost:8080/api/voucher-order/seckill/10") // 记得加上 /api
                .header("authorization", token)
                .execute();
        System.out.println(response.body());
    }

    @Test
    void loadShopData(){
        // 1. 查询店铺信息
        List<Shop> list = shopService.list();
        // 2. 把店铺按照typeId分组，id一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 分批完成写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1 获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2 获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3 写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                //stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000; //防止角标超出范围
            values[j] = "user_" + i;
            if(j == 999){
                // 发送到redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }

}