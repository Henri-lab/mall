# 05. Growth Roadmap

## 1. 初级 Java 后端必须稳住的能力

Java 基础：

```text
集合
泛型
异常
Stream
Optional
日期时间
反射基础
注解基础
枚举
接口和抽象类
```

JVM 基础：

```text
class 文件
类加载
堆和栈
GC 基础
字节码基础
javap
```

并发基础：

```text
线程
线程池
synchronized
volatile
ReentrantLock
AtomicInteger
ConcurrentHashMap
CompletableFuture
```

Spring：

```text
Bean 扫描
依赖注入
Bean 生命周期
@Configuration
@Bean
@Value
@ConfigurationProperties
AOP
@Transactional
Spring MVC
Spring Security
```

数据库：

```text
SQL
JOIN
索引
事务
隔离级别
行锁
慢查询
分页
```

工程能力：

```text
Maven 多模块
Git
Docker Compose
Swagger
日志
断点调试
Linux 基础
```

## 2. 用 mall 项目练什么

第一阶段：跑起来

```text
Docker 起 MySQL/Redis/MinIO
IDE debug mall-admin
打开 Swagger
调通一个 GET 接口
调通一个 POST 接口
```

第二阶段：读链路

```text
PmsProductController#create
UmsAdminController#login
PmsSkuStockController#update
OmsOrderController#list
```

每条链路都要能说清楚：

```text
请求路径
参数对象
调用哪个 Service
事务在哪里
Mapper 是哪个
SQL 在哪里
返回值怎么封装
```

第三阶段：改功能

建议练习：

1. 商品新增一个字段，从数据库到接口打通。
2. 商品列表加一个筛选条件。
3. 增加一个简单缓存。
4. 给某个接口加参数校验。
5. 给某个 Service 方法写事务失败回滚测试。

第四阶段：解释框架

能解释：

```text
为什么 mall-admin 能扫描 mall-security
为什么 @Value 能读 yml
为什么 @Transactional 会进 CglibAopProxy
为什么 Mapper 没实现类也能调用
为什么 Redis 挂了不能影响主业务
为什么断点有时不命中
```

## 3. 面试表达模板

介绍项目不要说：

```text
我看过一个商城项目
```

应该说：

```text
我调试过一个基于 Spring Boot 3 的多模块电商后台项目。
它分成 common、mbg、security、admin、portal、search 等模块。
admin 模块提供后台接口，security 模块封装 JWT 和 Spring Security，mbg 模块放 MyBatis Generator 生成的 model 和 mapper。
我重点跟过商品创建、后台登录、Redis 权限缓存、事务代理这些链路。
```

介绍一个接口：

```text
以商品创建为例，请求进入 PmsProductController#create，
Controller 调用 PmsProductService#create。
这个方法声明了 @Transactional，所以注入进 Controller 的 service 实际是 Spring AOP 代理。
真实执行进入 PmsProductServiceImpl#create 后，会先插入商品主表，再插入会员价、阶梯价、满减、SKU、商品参数、专题关联等子表。
如果中间失败，事务会回滚。
```

介绍调试经验：

```text
我遇到过断点打在 int count; 不生效的问题。
后来用 javap -c -l 看 LineNumberTable，发现该局部变量声明没有生成字节码，所以断点无法绑定。
这让我理解了 Java 调试不是按源码文本执行，而是依赖字节码和行号表。
```

介绍 Spring 经验：

```text
我排查过 @ConfigurationProperties 绑定不生效的问题。
原因是配置类被手动 @Bean new 出来，类上的 @ConfigurationProperties 没有按预期绑定。
后来改成在 @Bean 方法上加 @ConfigurationProperties，并避免重复声明 prefix。
```

这种表达比“我会 Spring Boot”强很多。

## 4. 如果目标是 Junior Java 后端

必须会：

```text
写 REST API
连 MySQL
写 SQL
处理事务
看懂日志
会 debug
会用 Git
会用 Docker 起依赖
能解释项目结构
```

加分项：

```text
Redis 缓存
Spring Security JWT
MyBatis XML
Swagger
单元测试
CI/CD 基础
Linux 部署
```

不要只停留在“能跑”。Junior 面试很看重你是否能解释：

```text
为什么这么写
出错怎么查
数据从哪来到哪去
```

## 5. 推荐补一个自己的项目

`mall` 是读企业项目，非常好。但还应该有一个自己从零写的小项目。

建议技术栈：

```text
Spring Boot 3
MySQL 或 PostgreSQL
MyBatis 或 JPA
Redis
JWT
Docker Compose
Swagger
JUnit/Testcontainers
GitHub Actions
```

项目不用大，但要完整：

```text
用户登录
权限控制
CRUD
分页查询
缓存
事务
异常处理
参数校验
Docker 一键启动
README 写清楚
```

题材可以是：

```text
任务管理系统
库存管理系统
博客后台
订单系统简化版
招聘投递系统
```

## 6. 每天上班前 30 分钟学习法

第一周：

```text
每天跟一个 Controller 到 Mapper
每天总结一个框架点
每天记录一个坑
```

第二周：

```text
改一个小功能
加一个 SQL 条件
写一个缓存
调一次事务回滚
```

第三周：

```text
整理面试表达
画模块图
画请求链路图
准备英文项目介绍
```

第四周：

```text
从零写小项目
接入 Docker
写 README
部署一次
```

## 7. 进阶判断标准

0.5 年水平：

```text
会写 Controller/Service/Mapper
能调接口
能改简单 bug
```

1 年水平：

```text
能独立改业务功能
能理解事务、缓存、权限
能根据日志排查大部分启动问题
```

2 年水平：

```text
能设计模块边界
能处理并发和事务问题
能优化 SQL 和缓存
能写测试和部署脚本
```

3 年水平：

```text
能独立负责一个子系统
能评估技术方案
能处理线上问题
能指导新人看项目
```

读懂 `mall` 项目可以帮你冲到 1-2 年理解力，但要真正坐实，需要自己改功能、写测试、处理真实 bug。

## 8. 最重要的心法

企业 Java 后端不是背注解。

真正的能力是：

```text
看到注解，知道背后的对象是谁
看到接口，知道实现在哪里
看到配置，知道谁读取它
看到报错，知道发生在哪个生命周期阶段
看到 SQL，知道会不会锁表/走索引/出事务问题
看到缓存，知道一致性风险
看到代理，知道真实方法在哪里
```

你现在问的问题已经进入正确方向了。继续这样从运行时机制往回看源码，比只刷教程有效得多。
