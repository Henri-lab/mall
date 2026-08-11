# Java Backend Growth Notes

这份目录是围绕当前 `mall` 项目整理的 Java 后端进阶笔记。目标不是背概念，而是能把一个企业 Java 项目从启动、依赖、Bean、Controller、Service、Mapper、事务、缓存、调试一路看穿。

建议阅读顺序：

1. `01-mall-project-map.md`：先搞清楚项目结构、模块关系、classpath、启动类扫描范围。
2. `02-spring-bean-and-aop.md`：理解 Bean 如何被扫描、注册、创建、注入、代理。
3. `03-debugging-and-bytecode.md`：补调试经验，尤其是断点、javap、代理、源码映射。
4. `04-enterprise-pitfalls.md`：企业项目常见坑和排查顺序。
5. `05-growth-roadmap.md`：按求职和实战能力整理学习路线。
6. `06-mall-order-and-mq-design.md`：订单、促销、退货、库存锁定的设计，以及 RabbitMQ 延迟队列实现超时取消。

读这份笔记时最好开着 IDE，对照项目文件看。不要只读结论，要顺着一个接口完整跟一遍：

```text
Controller -> Service -> Mapper -> XML SQL -> MySQL/Redis -> 返回结果
```

推荐第一条链路：

```text
PmsProductController#create
 -> PmsProductService#create
 -> PmsProductServiceImpl#create
 -> PmsProductMapper / 各类 Dao
 -> MySQL
```

推荐第二条链路：

```text
UmsAdminController#login
 -> UmsAdminServiceImpl#login
 -> Spring Security / JWT
 -> Redis cache
```

推荐第三条链路：

```text
Swagger 调接口
 -> Spring MVC
 -> Controller
 -> AOP proxy
 -> TransactionInterceptor
 -> Service impl
```

判断自己是否真的读懂：

- 能解释为什么 `mall-admin` 能扫描到 `mall-security`。
- 能解释 `@Value` 数据来自哪里。
- 能解释为什么断点在 `int count;` 不生效。
- 能解释为什么会进 `CglibAopProxy`。
- 能解释 `@Transactional` 不是普通注解，而是 AOP 代理增强。
- 能解释 Redis 在这个项目中缓存了什么。
- 能解释 `application.yml` 和 `application-dev.yml` 的加载关系。
- 能自己新增一个接口，并且写通 Controller、Service、Mapper、SQL。
