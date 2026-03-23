🍱 黑马点评优化版 (HMDP - Advanced Edition)
基于经典项目“黑马点评”进行二次开发与架构优化的实战级项目。在原有的 O2O（线上到线下）本地生活服务平台基础上，通过引入高级中间件和 AI 能力，大幅提升了系统在高并发场景下的可用性与用户体验。

✨ 核心优化与新特性
🚀 引入 RocketMQ 实现异步削峰：弃用了原版基础的队列实现，全面接入 RocketMQ。针对秒杀等高并发场景，通过配置 hmdp-producer-group 生产者组，实现了流量削峰与服务异步解耦，极大提升了系统的吞吐量与稳定性。

🔒 Redisson 分布式锁增强：集成了 Redisson 3.13.6，替换了原生的 Redis SETNX 分布式锁实现。利用 Redisson 的看门狗机制和可重入特性，彻底解决了秒杀和高并发缓存击穿场景下的锁超时与误删问题。

🤖 AI 商铺智能总结 (New Feature)：新增了前沿的 AI 集成功能模块。通过暴露 /shop-ai/summary/{shopId} 接口，系统能够智能提炼商铺的核心特色与用户评价，为用户提供一目了然的商铺摘要（Shop Summary）。

⚡ 基础秒杀模块完善：保留并优化了原有的秒杀券（SeckillVoucher）核心业务逻辑。

🛠️ 技术栈
核心框架: Spring Boot 2.7.4

持久层框架: MyBatis-Plus 3.5.2

数据库: MySQL 8.0+ (使用了 com.mysql.cj.jdbc.Driver)

缓存与分布式: Redis + Redisson

消息队列: Apache RocketMQ 2.2.3

工具库: Hutool 5.8.8, Lombok
