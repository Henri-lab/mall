# 02. Spring Bean, Injection, AOP

## 1. Bean 生命周期大图

一个普通 Spring Bean 大概经历：

```text
扫描 class
 -> 注册 BeanDefinition
 -> 实例化对象
 -> 属性注入 / 依赖注入
 -> BeanPostProcessor before init
 -> @PostConstruct / InitializingBean
 -> BeanPostProcessor after init
 -> 可能被 AOP 包装成代理
 -> 放入容器
 -> 被其他 Bean 注入使用
```

注意几个层次：

```text
BeanDefinition = 将来要创建 Bean 的描述
Bean instance = 真正创建出来的对象
Proxy Bean = 被 AOP 包装后的对象
```

很多启动错误不是“创建对象”时报的，而是在扫描、解析、注册 BeanDefinition 时就报了。

## 2. @ConfigurationProperties 的经验

我们遇到过这个问题：

```java
@ConfigurationProperties(prefix = "secure.ignored")
public class IgnoreUrlsConfig {
    private List<String> urls = new ArrayList<>();
}
```

如果类本身不是 Spring 扫描出来的 Bean，而是在配置类里手动 `new` 出来的：

```java
@Bean
public IgnoreUrlsConfig ignoreUrlsConfig() {
    return new IgnoreUrlsConfig();
}
```

那么更稳的写法是：

```java
@Bean
@ConfigurationProperties(prefix = "secure.ignored")
public IgnoreUrlsConfig ignoreUrlsConfig() {
    return new IgnoreUrlsConfig();
}
```

不要类上和方法上同时写：

```java
@ConfigurationProperties(prefix = "secure.ignored")
```

否则会出现重复配置定义。

记忆规则：

如果配置类自己被扫描：

```java
@Component
@ConfigurationProperties(prefix = "xxx")
public class XxxProperties {}
```

如果通过 `@Bean` 手动创建：

```java
@Bean
@ConfigurationProperties(prefix = "xxx")
public XxxProperties xxxProperties() {
    return new XxxProperties();
}
```

二选一。

## 3. @Value 注入

例子：

```java
@Value("${aliyun.oss.accessKeyId}")
private String ALIYUN_OSS_ACCESSKEYID;
```

这个值来自 Spring Environment，来源可以是：

```text
application.yml
application-dev.yml
JVM -D 参数
命令行参数
环境变量
外部配置文件
```

`@Value` 字段注入发生在 Bean 创建阶段：

```text
构造对象
 -> 处理 @Value/@Autowired 字段
 -> 调用 @PostConstruct
 -> 调用 @Bean 方法 / 初始化逻辑
```

调试 `@Value` 的实用方法：

1. 在 `@PostConstruct` 里打断点，看注入后的值。
2. 在使用该字段的方法里打断点，例如 `OssConfig#ossClient()`。
3. 真想看内部注入瞬间，断在：

```text
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor$AutowiredFieldElement.inject
```

更推荐的写法是把字段注入改成方法参数注入：

```java
@Bean
public OSSClient ossClient(
        @Value("${aliyun.oss.endpoint}") String endpoint,
        @Value("${aliyun.oss.accessKeyId}") String accessKeyId,
        @Value("${aliyun.oss.accessKeySecret}") String accessKeySecret) {
    return new OSSClient(endpoint, accessKeyId, accessKeySecret);
}
```

这样 debug 参数最直观。

## 4. 注入的价值

注入不是为了“拿一个对象”这么简单，而是拿一个被 Spring 管理好的对象。

Spring 管理对象意味着：

```text
依赖已经装配
配置已经注入
生命周期已管理
事务/缓存/权限等 AOP 能生效
作用域可控
```

如果你自己 `new`：

```java
new PmsProductServiceImpl()
```

它里面的 mapper、dao、service 字段都不会自动注入，`@Transactional` 也不会生效。

## 5. 单例、原型和 request scope

Spring 默认 Bean 是单例：

```java
@Service
public class XxxService {}
```

等价于：

```java
@Scope("singleton")
```

如果要每次获取新对象：

```java
@Component
@Scope("prototype")
public class TaskContext {}
```

注意坑：

```java
@Service
public class OrderService {
    @Autowired
    private TaskContext context;
}
```

如果 `OrderService` 是单例，`TaskContext` 即使是 prototype，也只会在 `OrderService` 创建时注入一次。

真正每次拿新对象：

```java
@Autowired
private ObjectProvider<TaskContext> contextProvider;

public void handle() {
    TaskContext context = contextProvider.getObject();
}
```

Web 场景常见：

```java
@RequestScope
```

每个 HTTP 请求一个对象。

## 6. AOP 和 CGLIB 代理

当你看到：

```text
CglibAopProxy
```

说明当前调用进入了 Spring AOP 代理。

例如：

```java
@Transactional
int create(PmsProductParam productParam);
```

调用链可能是：

```text
Controller
 -> productService 代理对象
 -> CglibAopProxy
 -> TransactionInterceptor
 -> PmsProductServiceImpl#create
 -> commit / rollback
```

代理对象通常在 Bean 创建阶段生成，运行时调用方法时执行代理逻辑。

关键点：

```text
启动时创建代理对象
注入给 Controller 的是代理对象
运行时先进入代理，再进入真实方法
```

## 7. JDK 代理和 CGLIB 代理

JDK 动态代理：

```text
基于接口生成代理
```

CGLIB 代理：

```text
基于目标类生成子类代理
```

Spring Boot 默认经常使用 CGLIB，所以你可能看到：

```text
PmsProductServiceImpl$$SpringCGLIB$$0
```

调试时可以在断点处看：

```java
productService.getClass()
```

也可以看真实目标类：

```java
org.springframework.aop.support.AopUtils.getTargetClass(productService)
```

## 8. 事务不是魔法

`@Transactional` 本质是 AOP。

大概逻辑：

```text
进入代理
 -> 获取数据库连接
 -> 开启事务
 -> 执行业务方法
 -> 成功提交
 -> 异常回滚
```

常见坑：

1. 自己 new 的对象，事务不生效。
2. 同一个类内部方法互相调用，可能绕过代理，事务不生效。
3. 默认只对 RuntimeException 回滚。
4. private 方法不能被 Spring AOP 正常代理。
5. 方法必须通过 Spring 管理的代理对象调用。

## 9. Bean 调试 hook

Spring 提供了扩展点：

```java
BeanFactoryPostProcessor
BeanPostProcessor
InstantiationAwareBeanPostProcessor
ApplicationRunner
ApplicationListener
```

我们加过一个调试类：

```text
mall-admin/src/main/java/com/macro/mall/debug/BeanLifecycleLogger.java
```

它可以打印：

```text
BeanDefinition 注册顺序
Bean 初始化顺序
```

但要记住：

```text
注册顺序 != 初始化顺序
初始化顺序受依赖关系影响
```
