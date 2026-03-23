package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //用String实现
        String key = CACHE_SHOP_TYPE_KEY;
        // 1.从redis查询商铺类型缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopTypeJson)){
            // 3.存在，直接返回
            List<ShopType> shopType = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopType);
        }
        // 4.不存在，根据id查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        // 5.数据库中不存在，返回错误
        if (shopTypeList == null || shopTypeList.isEmpty()){
            return Result.fail("商铺类型不存在！");
        }
        // 6.数据库中存在，写入redis
        String jsonStr = JSONUtil.toJsonStr(shopTypeList);
        stringRedisTemplate.opsForValue().set(key, jsonStr);
        // 7.返回
        return Result.ok(shopTypeList);
    }
}
