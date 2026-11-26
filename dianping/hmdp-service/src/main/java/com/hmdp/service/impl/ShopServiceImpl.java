package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.metrics.MetricsRecorder;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.shopreview.dto.CreateShopReviewRequest;
import com.hmdp.shopreview.service.ShopReviewRewardService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.MultiLevelCacheService;
import com.hmdp.utils.RedisBloomFilter;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Generated;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static com.hmdp.utils.RedisConstants.*;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient clientClient;

    /** 多级缓存服务（批次3 ②：L1 本地缓存叠加在逻辑过期之上） */
    @Resource
    private MultiLevelCacheService multiLevelCacheService;

    /** 本地缓存开关：false = 完全回退到单级 Redis 链路（批次3 之前的原行为） */
    @Value("${cache.local.enabled:true}")
    private boolean localCacheEnabled;

    @Resource
    private ShopReviewRewardService shopReviewRewardService;

    /**
     * 布隆前置守卫（批次4）：确定不存在的 id 在进 缓存→负缓存→DB 整条链之前就拒绝。
     * 开关关闭（bloom.shop.enabled=false）时 Bean 不装配、字段为 null，行为与无守卫完全一致；
     * 守卫内部 fail-open（未初始化/Redis 异常时放行），绝不误杀真实数据。
     */
    @Autowired(required = false)
    private RedisBloomFilter shopBloomFilter;

    /** 提交后缓存驱逐失败打点：失败只告警，不能让已提交的写请求对外表现为失败 */
    @Resource
    private MetricsRecorder metricsRecorder;

    @Override
    public Result queryById(Long id){
        // 布隆前置守卫：可能存在（或 fail-open 放行）才走多级缓存/负缓存/DB；确定不存在直接拒绝，
        // 恶意随机 id 打不到缓存也打不到 DB，穿透防线从"事后兜底"前移到"事前拦截"
        if (shopBloomFilter != null && !shopBloomFilter.mightContain(id)) {
            return Result.fail("店铺不存在！");
        }
        Shop shop = queryShopNoBloomGuard(id);
        if(shop==null){

            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);

    }

    /**
     * 不带布隆守卫的店铺基础查询，供商详链路复用（批次：商详接缓存）。
     * <p>与 {@link #queryById(Long)} 共用同一条多级缓存链路（L1 → L2 逻辑过期 → DB），
     * 但跳过布隆前置守卫：布隆只在启动/每日 rebuild 时全量同步，新增店铺在下次 rebuild
     * 前会被误判为"不存在"。商详 base 只要 DB 里存在的店铺，不能因为布隆重建滞后返回空页，
     * 故走这里；穿透防护由 {@code loadOnMiss} 的空值标记 + 互斥锁兜底。</p>
     * <p>查不到返回 null，语义与 {@code getById} 一致，由调用方决定如何解包。</p>
     */
    public Shop queryShopNoBloomGuard(Long id) {
        return localCacheEnabled
                ? multiLevelCacheService.queryWithLocalCache(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES)
                : clientClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    /*缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = clientClient
               // .queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if(shop==null){
//            return Result.fail("店铺不存在！");
//        }
         */

      /*   public Shop queryWithLogicalExpire(Long id)   {
//        //1.尝试从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //2.判断缓存是否存在
//        if(StrUtil.isBlank(shopJson)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
//            //3.不存在，返回商铺信息
//            return null;
//
//        }
//
//        //4.存在，将json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //5.判断是否过期
//        if(expireTime.isAfter(LocalDateTime.now())) {
//            //5.1.未过期，直接返回店铺信息
//            return shop;
//        }
//        //5.2.已过期，需要返回缓存重建
//        //6.缓存重建
//        //6.1.获取互斥锁
//        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
//        boolean isLock = tryLock(lockKey);
//        //6.2.判断是否获取锁成功
//        if(isLock){
//            //  6.3.成功，开启独立线程实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    //重建缓存
//                    this.saveShop2Redis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    //释放锁
//                    unLock(lockKey);
//                }
//            });
//
//        }
//
//        //6.4.返回过期的商铺信息
//        return shop;
//
//    }
    /**
     * 互斥锁解决缓存穿透
     * @param id
     * @return
     * @throws InterruptedException
     */
    /* public Shop queryWithMutex(Long id) throws InterruptedException {
        //1.尝试从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //2.判断缓存是否存在
        if(StrUtil.isNotBlank(shopJson)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            //3.存在，返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断是否为空值
        if(shopJson!=null){
            return null;
        }
        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey="lock:shop:"+id;
        Shop shop=null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock) {
                //4.3失败，则休眠重试
               Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4.成功，根据id查询数据库
            shop = getById(id);
            //模拟重建的延迟
            Thread.sleep(200);
            //5.判断数据库中是否存在
            if(shop==null){
                //6.不存在，返回错误状态码
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //7.存在，写入redis，返回商铺信息
            String newShopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,newShopJson,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unLock(lockKey);
        }

        //9.返回
        return shop;

    }*/
    /**
     * 缓存穿透
     * @param id
     * @return
     */
    /*
//        //1.尝试从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //2.判断缓存是否存在
//        if(StrUtil.isNotBlank(shopJson)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
//            //3.存在，返回商铺信息
//            return JSONUtil.toBean(shopJson, Shop.class);
//
//        }
//        //判断是否为空值
//        if(shopJson!=null){
//            return null;
//        }
//        //4.不存在，根据id查询数据库
//        Shop shop = getById(id);
//        //5.判断数据库中是否存在
//        if(shop==null){
//            //6.不存在，返回错误状态码
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return null;
//        }
//        //7.存在，写入redis，返回商铺信息
//        String newShopJson = JSONUtil.toJsonStr(shop);
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,newShopJson,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        return shop;
//
//    }

     */


    public void saveShop2Redis(Long id,Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装成逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //1.先修改数据库
        updateById(shop);
        //2.删除缓存：本地+Redis 双删，并广播其他实例失效（批次3 ②）
        //   必须等事务提交后再删——提交前删，删除到提交之间的读请求会回源读到旧值并回填，
        //   提交后缓存里反而挂着旧值，只能等逻辑过期（30 分钟）自愈
        evictCacheAfterCommit(shop.getId());
        return Result.ok();
    }

    /**
     * 把"双删 + 广播"推迟到事务提交成功后执行。
     *
     * <p>无事务上下文（内部直调、单测、非事务调用方）没有"提交"可等，立即同步执行，
     * 语义与改造前一致——不能因为拿不到事务就跳过删除。</p>
     */
    private void evictCacheAfterCommit(Long shopId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeEvictAndBroadcast(shopId);
            return;
        }
        // 同一事务内多次 update 同一店铺只广播一次：afterCommit 里的 syncSend 要等 broker ack，
        // 每注册一个同步器就多一次 RTT，重复 key 直接并进已有同步器
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof AfterCommitEviction) {
                ((AfterCommitEviction) synchronization).add(shopId);
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new AfterCommitEviction(shopId));
    }

    /**
     * 提交后执行驱逐：此时 DB 已提交，缓存侧任何失败都不能反向影响调用方——
     * 只告警 + 打点，脏读窗口交给 L1/L2 自身的逻辑过期兜底。
     */
    private void safeEvictAndBroadcast(Long shopId) {
        try {
            multiLevelCacheService.evictAndBroadcast(CACHE_SHOP_KEY, shopId);
        } catch (Exception e) {
            metricsRecorder.incCacheEvictFailure();
            log.warn("[多级缓存] 事务提交后驱逐缓存失败，等待逻辑过期兜底, shopId={}", shopId, e);
        }
    }

    /**
     * 事务提交后统一驱逐的同步器：收集本事务内需要失效的店铺 id，提交后逐个执行。
     * 回滚时数据没变，不删缓存，只留 debug 解释"为什么没删"。
     */
    private final class AfterCommitEviction implements TransactionSynchronization {

        private final Set<Long> shopIds = new LinkedHashSet<>();

        private AfterCommitEviction(Long shopId) {
            this.shopIds.add(shopId);
        }

        private void add(Long shopId) {
            this.shopIds.add(shopId);
        }

        @Override
        public void afterCommit() {
            for (Long shopId : shopIds) {
                safeEvictAndBroadcast(shopId);
            }
        }

        @Override
        public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
                log.debug("[多级缓存] 事务未提交(status={})，跳过缓存驱逐, shopIds={}", status, shopIds);
            }
        }
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.是否根据坐标查询
        if(x==null||y==null){
            //不需要坐标查询，该数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询redis，按照距离排序，分页。结果：shopId,distance
        String key = SHOP_GEO_KEY + typeId;
        //在 Redis 中按地理坐标（x, y）查询距离当前用户位置 5000 米内的商店
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        //4.解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //4.1.截取从from到end部分  跳过前 from 个结果，实现分页
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            //4.2.获取店铺id
            String shopIdStr=result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(shops);
    }

    @Override
    public Result createShopReview(Long userId, CreateShopReviewRequest request) {
        return Result.ok(shopReviewRewardService.createReview(userId, request));
    }
}
