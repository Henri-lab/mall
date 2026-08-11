# 06. 电商订单设计：下单、促销、退货与消息队列

> 阅读目标：跟着 `mall-portal` 把一笔订单从购物车到支付、再到超时取消/退货完整走一遍，
> 搞清楚金额是怎么算出来的、库存是怎么锁的、优惠券和积分怎么分摊、退货怎么走状态机，
> 最后弄懂 RabbitMQ 延迟队列（TTL + 死信）是怎么实现"超时自动取消订单"的。

## 1. 订单模块全景图

订单相关代码主要分两块：

```text
mall-portal（前台，用户侧）
  controller/OmsPortalOrderController        下单、取消、确认收货、删除
  controller/OmsPortalOrderReturnApplyController  申请退货
  service/impl/OmsPortalOrderServiceImpl     核心：确认单、下单、支付回调、取消
  service/impl/OmsPromotionServiceImpl       核心：购物车促销价格计算
  service/impl/UmsMemberCouponServiceImpl    优惠券领取/可用性判断
  service/impl/OmsPortalOrderReturnApplyServiceImpl 退货申请落库
  component/CancelOrderSender / CancelOrderReceiver  延迟消息收发
  config/RabbitMqConfig                      延迟队列定义
  domain/QueueEnum                           交换机、队列、路由键定义

mall-admin（后台，运营侧）
  controller/OmsOrderController              查单、发货、关闭、改价、改地址、备注
  controller/OmsOrderReturnApplyController   退货申请处理（确认/完成/拒绝）
  service/impl/OmsOrderServiceImpl           后台订单操作 + 操作记录
  service/impl/OmsOrderReturnApplyServiceImpl 退货状态机
```

核心表（`document/sql/mall.sql`）：

```text
oms_order                   订单主表：金额、状态、收货人、支付信息
oms_order_item              订单商品明细：下单时快照商品信息 + 各项优惠分摊
oms_order_operate_history   订单操作记录：谁在什么时候做了什么
oms_order_setting           超时设置：普通订单超时分钟数、自动确认天数
oms_order_return_apply      退货申请表：状态机 + 处理人 + 退款金额
pms_sku_stock               SKU 库存：stock 真实库存 + lock_stock 锁定库存
pms_product_ladder          打折优惠：满 N 件打 X 折
pms_product_full_reduction  满减优惠：满 X 减 Y
sms_coupon / sms_coupon_history          优惠券定义 / 用户领取记录
sms_coupon_product_relation             优惠券-商品关系（指定商品券）
sms_coupon_product_category_relation     优惠券-分类关系（指定分类券）
sms_flash_promotion / _session / _product_relation  秒杀活动、场次、商品
```

一句话概括架构：**下单不是一个接口，而是一条"确认单 → 金额计算 → 库存锁定 → 落库 → 发送延迟取消消息"的流水线**。

## 2. 订单主表字段：理解订单 = 金额 + 状态 + 快照

`OmsOrder`（model 位于 `mall-mbg`）关键字段：

```text
member_id / order_sn            归属用户、订单号
total_amount                    商品总金额（下单时原价 × 数量）
pay_amount                      实付金额
freight_amount                  运费
promotion_amount                活动优惠（单品/打折/满减合计）
coupon_amount                   优惠券优惠
integration_amount              积分抵扣金额
discount_amount                 后台改价折扣（预留，下单时恒为 0）
integration / growth            使用的积分 / 赠送的成长值
status                          0待付款 1待发货 2已发货 3已完成 4已关闭 5无效
order_type                      0正常订单 1秒杀订单（预留）
pay_type / source_type          支付方式 / 订单来源
receiver_*                      收货人快照（下单时复制，不能读地址表）
auto_confirm_day                自动确认收货天数（来自 oms_order_setting）
payment_time / delivery_time / receive_time  关键时间点
```

为什么订单里要"快照"这么多商品字段（`oms_order_item` 里有商品名、图片、SKU、价格、促销名）？
因为商品信息随时会改，而订单是历史事实，**订单行必须保存下单那一刻的完整信息**，这是电商订单设计的第一个核心思想。

## 3. 下单主流程：一步步跟着代码走

入口：`POST /order/generateOrder` → `OmsPortalOrderServiceImpl#generateOrder`。

### 3.1 先看确认单（generateConfirmOrder）

下单前前端先调 `POST /order/generateConfirmOrder` 拿到：

```text
cartPromotionItemList      购物车 + 促销计算后的价格
memberReceiveAddressList   收货地址
couponHistoryDetailList    当前购物车可用的优惠券（已过滤门槛）
memberIntegration          用户积分
integrationConsumeSetting  积分使用规则
calcAmount                 总金额 / 活动优惠 / 应付金额
```

前端展示完这些信息，用户选优惠券、填积分、选地址后，才正式下单。

### 3.2 下单（generateOrder）的执行顺序

```text
1. 校验收货地址
2. 从购物车重新计算促销价（listPromotion → calcCartPromotion）
3. 生成 oms_order_item 列表（商品快照 + 活动优惠 reduceAmount）
4. hasStock：判断每个 SKU 真实库存是否够
5. 优惠券：不用则 couponAmount=0；用则校验可用性并按比例分摊
6. 积分：校验积分数量、门槛、与优惠券互斥、最高抵扣比例，再按比例分摊
7. handleRealAmount：每个商品实付 = 原价 - 促销 - 优惠券 - 积分
8. lockStock：给每个 SKU 的 lock_stock 加下单数量
9. 组装 OmsOrder：金额、状态 0、订单号、收货人快照、自动确认天数
10. 插入 oms_order + oms_order_item
11. 更新优惠券使用状态为已使用；扣除用户积分
12. 删除购物车中已下单的商品
13. sendDelayMessageCancelOrder：发延迟消息，超时自动取消
```

注意两点：

- 第 2 步**不信任前端传的价格**，全部以服务端重新计算为准。前端只传 `cartIds`、`couponId`、`useIntegration`、`payType`、地址 ID。
- 第 8 步在金额算完之后、落库之前锁定库存，锁的是 `lock_stock`，不是直接减 `stock`。

### 3.3 金额计算公式（全项目最重要的一段）

订单级：

```text
payAmount = totalAmount + freightAmount - promotionAmount - couponAmount - integrationAmount
```

对应代码 `calcPayAmount`。

商品级（`oms_order_item`）：

```text
realAmount = productPrice - promotionAmount - couponAmount - integrationAmount
```

对应代码 `handleRealAmount`。

关键设计：**优惠要分摊到每个商品行**。满减、优惠券、积分都不是只记一个订单总额，而是拆到明细行，这样退货时才能知道"退这一个商品该退多少钱"。

### 3.4 订单号生成（Redis 自增）

```java
// 18位：8位日期 + 2位来源 + 2位支付方式 + 6位以上当日自增id
String key = REDIS_DATABASE + ":" + REDIS_KEY_ORDER_ID + date;   // mall:oms:orderId20260811
Long increment = redisService.incr(key, 1);
```

用 Redis `INCR` 保证同一天内订单号唯一且趋势递增。这就是为什么订单号"看起来有规律"：前 8 位是日期。

## 4. 促销与折扣：OmsPromotionServiceImpl

购物车促销计算入口是 `OmsCartItemServiceImpl#listPromotion` → `OmsPromotionService#calcCartPromotion`。

核心思路：**先按 SPU 分组，再按 promotion_type 计算**。

### 4.1 促销类型

```text
promotion_type = 0  无优惠
promotion_type = 1  单品促销：SKU 上有 promotion_price（促销价）
promotion_type = 3  打折优惠：按 pms_product_ladder（满 N 件打 X 折）
promotion_type = 4  满减优惠：按 pms_product_full_reduction（满 X 减 Y）
```

### 4.2 三种优惠的计算

单品促销：

```text
reduceAmount = 原价(sku.price) - 促销价(sku.promotion_price)
```

打折（阶梯）：

```text
先按 ladder.count 降序找"当前数量满足的最高档"
reduceAmount = 原价 - 折扣 × 原价
```

满减：

```text
先按 full_price 降序找"当前总价满足的最高档"
reduceAmount = (商品原价 / SPU 内商品总价) × 满减金额   // 按金额占比分摊
```

对应的辅助方法：

```text
groupCartItemBySpu           按商品分组，活动按 SPU 算而不是按 SKU
getProductLadder             选满足条件的最高档打折
getProductFullReduction      选满足条件的最高档满减
handleNoReduce               不满足条件/无活动：reduceAmount = 0，促销文案"无优惠"
```

注意一个细节：**打折和满减是同一 SPU 内所有 SKU 合并计算**（比如一个商品买 5 件，其中 2 件红色 3 件蓝色，按 5 件找档位）。这是促销引擎最常见的坑：活动作用于 SPU，而库存、下单作用于 SKU。

## 5. 优惠券体系

### 5.1 定义（SmsCoupon）

```text
type       优惠券类型（满减券等）
use_type   0 全场通用 / 1 指定分类 / 2 指定商品
amount     面额
min_point  使用门槛（满多少钱可用）
count      发行总量
per_limit  每人限领
start_time / end_time  有效期
```

### 5.2 领取（UmsMemberCouponServiceImpl#add）

领取校验链：

```text
1. 优惠券存在？
2. count > 0（还没领完）
3. 当前时间 >= enable_time
4. 该用户已领数量 < per_limit
5. 插入 sms_coupon_history（券码 16 位：时间戳后8位 + 4位随机 + 用户ID后4位）
6. count - 1，receive_count + 1
```

### 5.3 下单时可用性判断（listCart）

遍历用户所有券，按 use_type 分别计算"符合条件的商品总价"：

```text
全场通用：购物车总价 >= min_point
指定分类：分类商品总价 >= min_point
指定商品：指定商品总价 >= min_point
且 end_time 未过期
```

能用的进 `enableList`，不能用的进 `disableList`。

### 5.4 优惠券分摊（calcPerCouponAmount）

```text
couponAmount(每件) = (商品价格 / 可用商品总价) × 券面额
```

即按金额占比把整张券摊到每个适用商品行上。这是"订单行分摊"思想的第二个体现。

### 5.5 使用与回滚

```text
下单成功：updateCouponStatus(couponId, memberId, 1)   // 已使用
取消/超时：updateCouponStatus(couponId, memberId, 0)   // 回滚为未使用
```

## 6. 积分抵扣

积分使用规则配置在 `ums_integration_consume_setting`：

```text
use_unit              每多少积分抵 1 元
coupon_status         0 不能与优惠券共用，1 可以共用
max_percent_per_order 最高抵扣订单金额百分比
```

校验链（`getUseIntegrationAmount`）：

```text
1. useIntegration > 用户当前积分 → 不可用
2. 用了优惠券且 coupon_status=0 → 不可用
3. useIntegration < use_unit → 不可用
4. 抵扣金额 > totalAmount × max_percent_per_order → 不可用
```

分摊方式同优惠券：按 `(商品价格 / 总价) × 总抵扣金额` 摊到每个商品行。

## 7. 库存模型：stock 与 lock_stock

这是本项目最值得学的部分之一：

```text
pms_sku_stock.stock       真实库存
pms_sku_stock.lock_stock  锁定库存（已下单未支付的占用）

可售库存 = stock - lock_stock（代码里叫 realStock）
```

三个关键动作：

```text
下单：lock_stock += 数量          （lockStock）
支付成功：stock -= 数量, lock_stock -= 数量   （paySuccess → updateSkuStock）
取消/超时：lock_stock -= 数量       （cancelOrder / cancelTimeOutOrder → releaseSkuStockLock）
```

这套"先锁定后扣减"模型，保证**未支付订单不会把商品卖给别人**，同时**支付后真实库存准确减少**。

⚠️ 也要看到它的短板（后面学习清单会展开）：

- `lockStock` 是"读出来再写回去"（`selectByPrimaryKey` + `setLockStock` + `update`），没有条件更新/乐观锁/分布式锁，高并发下会丢更新。
- `updateSkuStock` 用 `CASE WHEN` 批量更新，比一条条更新快，但也没有版本号防并发。
- 下单多表写入没有 `@Transactional`，中途抛异常会出现"库存锁了但订单没建"的脏数据。

## 8. 订单状态机与后台操作

### 8.1 状态定义

```text
0 待付款 → 1 待发货 → 2 已发货 → 3 已完成
        ↘ 4 已关闭（超时/用户取消/后台关闭）
5 无效订单（预留）
```

状态流转点：

```text
下单            0
支付成功回调      0 → 1（同时扣真实库存）
用户取消 / 超时    0 → 4
后台发货         1 → 2
用户确认收货      2 → 3
后台关闭         任意未删 → 4
删除            仅 3/4 可删除（delete_status = 1，逻辑删除）
```

### 8.2 后台操作必写操作记录

`mall-admin` 的 `OmsOrderServiceImpl` 里，发货、关闭、改地址、改费用、备注，每个操作都：

```text
1. 更新 oms_order
2. 插入 oms_order_operate_history（操作人、时间、订单状态、备注）
```

这是电商后台的标配：**订单任何金额/状态变化都要留痕**，出问题能追责。

### 8.3 支付回调

`paySuccess(orderId, payType)`：

```text
status → 1，payment_time，pay_type
然后 updateSkuStock：真实库存扣减、锁定库存释放
```

支付宝回调入口在 `AlipayController` / `AlipayServiceImpl`（回调里通过 orderSn 查单再走 `paySuccessByOrderSn`）。

## 9. 退货流程：OmsOrderReturnApply 状态机

### 9.1 用户申请（前台）

`POST /returnApply/create`，参数见 `OmsOrderReturnApplyParam`：

```text
orderId / productId / orderSn
returnName / returnPhone        退货人信息
productPic / productName / productAttr   商品快照
productCount / productPrice / productRealPrice
reason / description / proofPics  原因、描述、凭证图
```

落库时只做一件事：`status = 0`（待处理），其余字段复制。

### 9.2 后台处理（状态机）

`OmsOrderReturnApplyServiceImpl#updateStatus`：

```text
0 待处理
  → 1 确认退货：记录 return_amount（应退金额）、company_address_id（退货地址）、处理人/时间/备注
  → 3 拒绝退货：记录处理人/时间/备注
1 确认退货
  → 2 完成退货：记录收货人/收货时间/收货备注
```

只有 `status = 3`（已拒绝）的申请允许删除。

### 9.3 退货流程的短板（需要补的部分）

这个版本只做到了"申请 + 审核状态机"：

```text
✗ 没有自动回补库存
✗ 没有回滚优惠券 / 返还积分
✗ 没有退款流水（应退金额只是记录，没对接支付退款）
✗ 没有按商品明细行的优惠分摊计算"单件应退金额"
```

面试/实战中要能补上：**退哪个商品行 → 该行实付 realAmount → 按行退库存 → 按比例退券/积分 → 调支付退款 → 记退款流水**。

## 10. 消息队列：RabbitMQ 延迟队列实现"超时自动取消"

这是本项目里 MQ 最完整的一个玩法，链路如下：

```text
下单成功
  → sendDelayMessageCancelOrder
    → 读 oms_order_setting.normal_order_overtime（分钟）
    → CancelOrderSender 发消息到 TTL 队列，setExpiration(分钟 × 60 × 1000)
  → TTL 队列里躺 normal_order_overtime 分钟
    → 到期消息变成死信，转发到 cancel 队列（死信交换机 + 死信路由键）
  → CancelOrderReceiver @RabbitListener 消费
    → portalOrderService.cancelOrder(orderId)
      → status=0 才取消，改 4
      → 释放库存锁定
      → 优惠券回滚为未使用
      → 返还积分
```

### 10.1 三件套定义

`QueueEnum`（枚举集中管理交换机/队列/路由键）：

```text
QUEUE_ORDER_CANCEL    exchange=mall.order.direct      queue=mall.order.cancel
QUEUE_TTL_ORDER_CANCEL exchange=mall.order.direct.ttl  queue=mall.order.cancel.ttl
```

`RabbitMqConfig`：

```java
// 实际消费队列绑定的交换机
DirectExchange orderDirect()

// TTL 延迟队列绑定的交换机
DirectExchange orderTtlDirect()

// 实际消费队列
Queue orderQueue()

// 延迟队列：关键是这两个参数
Queue orderTtlQueue() {
    return QueueBuilder.durable(...)
        .withArgument("x-dead-letter-exchange", QUEUE_ORDER_CANCEL.getExchange())
        .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_CANCEL.getRouteKey())
        .build();
}

// 两个绑定：队列绑到各自交换机，路由键相同
Binding orderBinding()
Binding orderTtlBinding()
```

核心思想：**延迟 = TTL + 死信转发**。

```text
消息发到 TTL 队列 → 到点不被消费 → 变成死信 → 按 x-dead-letter-* 转发到真正的消费队列
```

### 10.2 发送端

`CancelOrderSender#sendMessage(orderId, delayTimes)`：

```java
amqpTemplate.convertAndSend(
    QUEUE_TTL_ORDER_CANCEL.getExchange(),
    QUEUE_TTL_ORDER_CANCEL.getRouteKey(),
    orderId,
    message -> {                       // MessagePostProcessor
        message.getMessageProperties().setExpiration(String.valueOf(delayTimes));
        return message;
    });
```

消息体就是 `orderId`，延迟时间由 `setExpiration` 控制。

### 10.3 消费端

```java
@Component
@RabbitListener(queues = "mall.order.cancel")
public class CancelOrderReceiver {
    @RabbitHandler
    public void handle(Long orderId) {
        portalOrderService.cancelOrder(orderId);
    }
}
```

### 10.4 取消逻辑（cancelOrder）为什么要加状态判断

```java
// 只查 status=0（待付款）且未删除的订单
example.createCriteria().andIdEqualTo(orderId).andStatusEqualTo(0).andDeleteStatusEqualTo(0);
```

因为用户可能在延迟消息到期前自己取消或已支付，**消费端必须幂等**：查不到就什么都不做。这是所有 MQ 消费者都要遵守的纪律。

### 10.5 兜底方案（定时扫描）

`OrderTimeOutCancelTask` 里有一个每 10 分钟扫一次超时订单的定时任务，但注意它的 `@Component` 被注释掉了，所以当前生效的只有延迟队列。生产环境通常两套都留：**延迟消息负责准时，定时任务负责兜底**（消息丢失也能扫回来）。

### 10.6 配置

`application-dev.yml`：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    virtual-host: /mall
    username: mall
    password: mall
```

`application.yml` 里 `rabbitmq.queue.name.cancelOrder: cancelOrderQueue` 是一段没被用到的遗留配置，真正的队列名硬编码在 `QueueEnum` 里，读代码时注意别被它带偏。

## 11. 值得注意的坑（边读边发现）

### 11.1 使用积分没有真正落库

`generateOrder` 里：

```java
orderMapper.insert(order);          // 先插入
if (orderParam.getUseIntegration() != null) {
    order.setUseIntegration(orderParam.getUseIntegration());  // insert 之后才 set，写不进数据库
}
```

`use_integration` 在 insert 之后才设置，这条数据没有持久化。后果：超时取消时 `cancelOrder` 读到的 `use_integration` 是 null，**积分可能不会返还**。

### 11.2 integration 字段被覆盖

```java
order.setIntegration(orderParam.getUseIntegration());   // 先写成"使用的积分"
...
order.setIntegration(calcGifIntegration(orderItemList)); // 后被"赠送积分"覆盖
```

同一个字段被两个含义不同的值先后赋值，属于典型 bug。学的时候要意识到：**"使用的积分"和"赠送的积分"必须拆成两个字段**。

### 11.3 无事务

`generateOrder` 涉及购物车、库存、订单、订单明细、优惠券、积分、Redis，一串操作没有 `@Transactional`。任何一步失败，前面的写入不会回滚。

### 11.4 库存无并发保护

```java
PmsSkuStock skuStock = skuStockMapper.selectByPrimaryKey(...);
skuStock.setLockStock(skuStock.getLockStock() + quantity);
skuStockMapper.updateByPrimaryKeySelective(skuStock);
```

这是"读-改-写"三步，高并发下两个请求会互相覆盖。正确做法是 SQL 原子更新（`update pms_sku_stock set lock_stock = lock_stock + #{n} where id=#{id} and stock - lock_stock >= #{n}`）或加锁/版本号。

### 11.5 秒杀只做了展示

首页有秒杀活动/场次/商品展示（`HomeServiceImpl#getHomeFlashPromotion`），但下单时 `order_type` 写死 0，**秒杀价、秒杀库存都没有真正接入下单链路**。学秒杀要另找完整项目，重点补：预扣库存、限流、排队、防超卖、Redis 预热。

## 12. 学习清单：怎么算"学会了"

对照代码，能不看源码回答以下问题：

```text
1. 一笔订单的实付金额由哪几部分组成？公式是什么？
2. 为什么满减/优惠券/积分都要"按比例分摊"到订单明细行？
3. 库存的 lock_stock 和 stock 分别什么时候加、什么时候减？
4. 下单、支付、取消三个动作分别怎么操作库存？
5. 促销的四种类型（无/单品/打折/满减）分别在哪张表、怎么算？
6. 优惠券的可用性是怎么按 use_type 判断的？下单后、取消后状态怎么变？
7. 订单状态机有几个状态？每个状态能发生哪些流转？
8. 后台改单为什么每次都要写操作记录表？
9. 退货申请的状态机是什么？为什么退货要保存商品快照和 real_price？
10. 延迟队列怎么实现？TTL 队列的两个 x-dead-letter 参数起什么作用？
11. 为什么消费者 cancelOrder 必须先查 status=0？
12. 这个项目的订单链路有哪些生产环境不能接受的缺陷？
```

推荐的完整阅读路径：

```text
OmsPortalOrderController#generateOrder
 → OmsPortalOrderServiceImpl#generateOrder
   → OmsCartItemServiceImpl#listPromotion
     → OmsPromotionServiceImpl#calcCartPromotion
   → UmsMemberCouponServiceImpl#listCart
   → 金额计算四件套：calcPayAmount / calcPromotionAmount / calcCouponAmount / calcIntegrationAmount
   → lockStock / hasStock
   → sendDelayMessageCancelOrder
     → CancelOrderSender → RabbitMqConfig → QueueEnum
       → CancelOrderReceiver → cancelOrder
```

一句话总结这篇：**mall 的订单设计教的是"金额拆分到行、库存先锁后扣、状态留痕、超时靠延迟队列"这四个套路，而它的坑教的是"事务、并发、幂等、退款"这四个生产必修课。**
