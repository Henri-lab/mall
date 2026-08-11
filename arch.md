下面是这个 `mall` 项目的核心架构图。它是一个 **Spring Boot 多模块单体/准微服务项目**：代码按模块拆分，但运行时主要是几个独立 Spring Boot 应用连接同一套基础设施。

**模块架构**
```mermaid
flowchart TD
    Root["mall 根工程<br/>pom packaging"]

    Root --> Common["mall-common<br/>通用工具/返回结果/异常/Redis封装"]
    Root --> MBG["mall-mbg<br/>MyBatis Generator<br/>Model/Mapper/Example"]
    Root --> Security["mall-security<br/>Spring Security/JWT/权限/Redis缓存"]
    Root --> Admin["mall-admin<br/>后台管理接口"]
    Root --> Portal["mall-portal<br/>前台商城接口"]
    Root --> Search["mall-search<br/>商品搜索服务"]
    Root --> Demo["mall-demo<br/>示例模块"]

    MBG --> Common
    Security --> Common
    Admin --> MBG
    Admin --> Security
    Portal --> MBG
    Portal --> Security
    Search --> MBG
```

**运行时架构**
```mermaid
flowchart LR
    AdminUI["后台前端/接口调用"] --> Admin["mall-admin<br/>后台管理 API<br/>:8080"]
    AppUI["移动端/商城前端"] --> Portal["mall-portal<br/>商城前台 API<br/>:8085"]
    SearchClient["搜索请求"] --> Search["mall-search<br/>商品搜索 API<br/>:8081"]

    Admin --> MySQL[("MySQL<br/>业务主库<br/>:3306")]
    Portal --> MySQL
    Search --> MySQL

    Admin --> Redis[("Redis<br/>缓存/Token/权限<br/>localhost:6380")]
    Portal --> Redis

    Portal --> Mongo[("MongoDB<br/>会员收藏/浏览历史<br/>:27017")]
    Portal --> RabbitMQ["RabbitMQ<br/>订单延迟取消<br/>:5672 / 管理台:15672"]

    Search --> ES[("Elasticsearch<br/>商品搜索索引<br/>:9200")]

    Admin --> MinIO["MinIO / OSS<br/>文件上传<br/>:9000"]
```

**典型请求分层**
```mermaid
flowchart TD
    Client["客户端请求"]
    Controller["Controller<br/>接收 HTTP 参数"]
    Service["Service / ServiceImpl<br/>业务编排"]
    Mapper["Mapper / DAO<br/>MyBatis 数据访问"]
    DB[("MySQL")]
    Cache[("Redis")]
    MQ["RabbitMQ"]
    Other["Mongo / ES / MinIO"]

    Client --> Controller
    Controller --> Service
    Service --> Mapper
    Mapper --> DB
    Service --> Cache
    Service --> MQ
    Service --> Other
```

**订单超时取消 MQ 架构**
```mermaid
sequenceDiagram
    participant User as 用户
    participant OrderService as OmsPortalOrderServiceImpl
    participant Sender as CancelOrderSender
    participant TTLQueue as TTL延迟队列
    participant DLX as 死信交换机
    participant CancelQueue as 取消订单队列
    participant Receiver as CancelOrderReceiver
    participant DB as MySQL

    User->>OrderService: 提交订单
    OrderService->>DB: 创建订单/订单项
    OrderService->>Sender: 发送延迟取消消息(orderId)
    Sender->>TTLQueue: 写入消息并设置TTL
    TTLQueue-->>DLX: TTL到期后变成死信
    DLX-->>CancelQueue: 路由到实际取消队列
    CancelQueue-->>Receiver: 消费orderId
    Receiver->>OrderService: cancelOrder(orderId)
    OrderService->>DB: 若未支付则关闭订单/释放库存
```

**模块职责简表**
| 模块 | 主要职责 |
|---|---|
| `mall-common` | 通用响应、异常、工具类、Redis 基础封装 |
| `mall-mbg` | MyBatis 生成的数据库 model、mapper、example |
| `mall-security` | JWT、Spring Security、权限过滤、用户认证 |
| `mall-admin` | 后台管理：商品、订单、权限、优惠券、文件上传 |
| `mall-portal` | 前台商城：会员、购物车、下单、支付、收藏、订单取消 |
| `mall-search` | 商品搜索，连接 Elasticsearch |
| `mall-demo` | 示例/演示模块 |

**一句话理解**
`mall-admin` 和 `mall-portal` 是业务入口；`mall-security` 提供认证授权；`mall-mbg` 提供数据库访问模型；`mall-common` 提供通用基础能力；MySQL 是主数据源，Redis 做缓存和登录态，RabbitMQ 做订单延迟取消，ES 做搜索，Mongo 存部分会员行为数据，MinIO/OSS 做文件存储。