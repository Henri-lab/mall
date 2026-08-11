# 04. Enterprise Java Pitfalls

## 1. 配置文件误判 schema

VS Code YAML 插件可能把 Spring Boot 的 `application.yml` 误识别成其他框架的 schema，例如 Enonic XP。

现象：

```text
Property spring is not allowed
```

不是 yml 语法错，而是 schema 套错了。

处理：

```json
"yaml.schemaStore.enable": false
```

Spring Boot 配置由 Spring Boot 插件处理。

## 2. profile 没生效

问题现象：

```text
本地明明配置 localhost，运行却连 redis/db
```

排查：

```text
启动日志里 The following 1 profile is active: "dev"
IDE VM 参数是否有 -Dspring.profiles.active
Docker ENTRYPOINT 是否写了 prod
application.yml 是否写了 spring.profiles.active
```

配置文件不是由 Maven 决定路径。Spring Boot 默认从 classpath 根目录加载：

```text
application.yml
application-{profile}.yml
```

## 3. classpath 造成“明明没用它怎么报错”

只要模块进了 pom 依赖，它的 class 就进入当前应用 classpath。

如果它的类在扫描范围内，并带有：

```java
@Configuration
@Component
@Service
```

就可能在启动时被扫描、解析、注册。

所以会出现：

```text
我没调用这个类，但启动时报它的错
```

原因可能是 Spring 启动时已经解析它了。

排查思路：

```text
看报错类在哪个模块
看当前启动模块 pom 是否依赖它
看启动类扫描范围
看类上是否有 Spring 注解
```

## 4. 旧 class 文件残留

我们遇到过：

```text
NoClassDefFoundError: DynamicSecurityService
Unresolved compilation problems
```

源码没问题，但 target/classes 里的 class 是坏的。

处理：

```bash
mvn clean compile
```

或 IDE：

```text
Rebuild Project
Java: Clean Java Language Server Workspace
```

经验：

```text
源码正确不代表运行的 class 正确
```

## 5. @Transactional 不生效

常见原因：

1. 方法不是通过 Spring 代理对象调用。
2. 同类内部方法互相调用。
3. private 方法。
4. 自己 new 对象。
5. 异常被 catch 吃掉。
6. 默认只回滚 RuntimeException。

排查：

```java
bean.getClass()
```

看是不是代理类。

事务断点可看：

```text
TransactionInterceptor
DataSourceTransactionManager
```

## 6. Redis 缓存不是数据库

Redis 适合：

```text
热点数据
登录状态
验证码
权限缓存
计数器
限流
分布式锁
排行榜
轻量队列
```

不适合把关键数据只放 Redis。

这个项目中：

```text
UmsAdminCacheServiceImpl
 -> RedisService
 -> RedisTemplate
```

缓存 key 例子：

```text
mall:ums:admin:admin
mall:ums:resourceList:1
```

配置来源：

```text
mall-admin/src/main/resources/application.yml
mall-admin/src/main/resources/application-dev.yml
```

注意：

```text
Redis value 可能是 Java 序列化对象，不一定能 redis-cli 直接读成人类可读 JSON
```

## 7. 锁技术不要混淆

`mall-admin` 没有显式 Java 锁：

```text
synchronized
ReentrantLock
Redisson
SETNX
select for update
```

主要是：

```text
@Transactional 带来的数据库事务锁
ConcurrentHashMap 这种并发容器
lock_stock 这种业务字段
```

真正下单锁库存逻辑在：

```text
mall-portal/src/main/java/com/macro/mall/portal/service/impl/OmsPortalOrderServiceImpl.java
```

企业里要分清：

```text
Java 线程锁
数据库行锁
Redis 分布式锁
业务锁定字段
消息队列串行化
```

它们解决的问题不同。

## 8. MyBatis 常见坑

1. Mapper 接口没有实现类，因为运行时动态代理生成。
2. XML namespace 必须和 Mapper 全限定名对应。
3. 参数名不对，SQL 取不到值。
4. 批量更新要注意事务。
5. 动态 SQL `<if>` 条件漏字段会导致 SQL 不符合预期。
6. 生成的 Example 查询对象可读性差，要学会看 Criteria。

看链路：

```text
ServiceImpl
 -> Mapper interface
 -> mapper XML
 -> SQL
```

## 9. Controller 不要写业务

Controller 只应该做：

```text
接收请求
参数绑定
基本校验
调用 Service
封装返回
```

业务逻辑应该在 Service。

如果 Controller 里开始出现大量 if/for/数据库调用，通常是分层坏味道。

## 10. DTO / Entity / VO 不要混用

常见对象：

```text
Entity / Model：数据库表对象
DTO：接口入参或服务传输对象
VO：返回给前端的视图对象
Param：查询或创建参数
BO：业务对象
```

这个项目里很多 DTO 继承或组合 model。学习时要注意：

```text
传给接口的不一定就是数据库表
返回给前端的不一定就是数据库表
```

## 11. 企业排错顺序

接口报错时，不要乱猜。

按顺序：

```text
请求是否到达 Controller
参数是否绑定成功
Controller 调的是哪个 Service Bean
Service 是否进入代理
真实 impl 是否执行
Mapper 是否调用
SQL 是否正确
数据库数据是否符合预期
事务是否提交或回滚
缓存是否命中旧数据
```

启动报错时：

```text
看最底层 Caused by
看第一个自己项目包名
看是 class not found / bean not found / property missing / SQL connection / port conflict
```

## 12. 企业项目经验重点

比“会写代码”更重要的是：

```text
知道代码运行在哪里
知道对象是谁创建的
知道配置从哪里来
知道代理有没有生效
知道事务边界在哪里
知道数据最终落在哪里
知道出错先看哪一层
```
