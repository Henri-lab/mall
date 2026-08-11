# 01. Mall Project Map

## 1. 这个项目是什么类型

这个 `mall` 是一个典型的多模块 Maven 单体项目，不是完整微服务。

物理上拆成多个 Maven module：

```text
mall
├── mall-common
├── mall-mbg
├── mall-security
├── mall-admin
├── mall-portal
├── mall-search
└── mall-demo
```

逻辑上运行时会按 classpath 组合。比如启动 `mall-admin` 时，不是只加载 `mall-admin` 目录，而是加载：

```text
mall-admin 自己的 classes
mall-admin 依赖模块的 classes
第三方 jar
```

所以你会看到不同模块里都有：

```text
src/main/java/com/macro/mall/...
```

它们运行时不是文件合并，而是 classpath 里形成一个大的包空间：

```text
com.macro.mall.*
```

## 2. 模块作用

`mall-common`

公共能力模块。放通用返回对象、分页对象、Redis 封装、通用配置等。

代表文件：

```text
mall-common/src/main/java/com/macro/mall/common/api/CommonResult.java
mall-common/src/main/java/com/macro/mall/common/api/ResultCode.java
mall-common/src/main/java/com/macro/mall/common/service/RedisService.java
mall-common/src/main/java/com/macro/mall/common/config/BaseRedisConfig.java
```

`mall-mbg`

MyBatis Generator 生成模块。放数据库表对应的 model、mapper、example。

代表文件：

```text
mall-mbg/src/main/java/com/macro/mall/model/PmsProduct.java
mall-mbg/src/main/java/com/macro/mall/mapper/PmsProductMapper.java
mall-mbg/src/main/resources/com/macro/mall/mapper/PmsProductMapper.xml
```

企业项目里这种生成代码很常见。优点是减少 CRUD 重复劳动，缺点是代码量大，初学时容易迷路。

`mall-security`

安全模块。封装 Spring Security、JWT、白名单、动态权限、Redis 异常兜底。

代表文件：

```text
mall-security/src/main/java/com/macro/mall/security/config/SecurityConfig.java
mall-security/src/main/java/com/macro/mall/security/config/CommonSecurityConfig.java
mall-security/src/main/java/com/macro/mall/security/component/JwtAuthenticationTokenFilter.java
mall-security/src/main/java/com/macro/mall/security/util/JwtTokenUtil.java
```

`mall-admin`

后台管理接口模块。管理员用的接口：商品管理、订单管理、会员管理、权限管理、文件上传等。

代表文件：

```text
mall-admin/src/main/java/com/macro/mall/MallAdminApplication.java
mall-admin/src/main/java/com/macro/mall/controller/PmsProductController.java
mall-admin/src/main/java/com/macro/mall/service/impl/PmsProductServiceImpl.java
```

`mall-portal`

前台商城接口模块。普通用户用的接口：登录、商品浏览、购物车、下单、订单支付、订单取消等。

真正的下单锁库存逻辑主要在这里，而不是 `mall-admin`。

代表文件：

```text
mall-portal/src/main/java/com/macro/mall/portal/MallPortalApplication.java
mall-portal/src/main/java/com/macro/mall/portal/service/impl/OmsPortalOrderServiceImpl.java
```

`mall-search`

搜索模块。集成 Elasticsearch，负责商品索引和搜索。

`mall-demo`

演示模块，非核心业务。

## 3. 为什么启动 mall-admin 会扫描到 mall-security

启动类：

```java
package com.macro.mall;

@SpringBootApplication
public class MallAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(MallAdminApplication.class, args);
    }
}
```

`@SpringBootApplication` 包含：

```java
@ComponentScan
```

默认扫描启动类所在包及子包：

```text
com.macro.mall
```

`mall-security` 里的类包名是：

```text
com.macro.mall.security.*
```

只要 `mall-admin/pom.xml` 依赖了 `mall-security`，这些类就进入 `mall-admin` 运行时 classpath。Spring 扫描 `com.macro.mall` 时就能看到它们。

关键规则：

```text
pom 依赖决定 classpath 里有没有这个类
@ComponentScan 决定 Spring 会不会扫描这个类
类上的注解决定会不会注册成 Bean
```

## 4. 不是所有 classpath 里的类都会成为 Bean

普通类不会自动成为 Bean：

```java
public class Demo {}
```

这些注解会让类成为候选 Bean：

```java
@Component
@Service
@Repository
@Controller
@RestController
@Configuration
```

也可以通过 `@Bean` 方法注册：

```java
@Bean
public JwtTokenUtil jwtTokenUtil() {
    return new JwtTokenUtil();
}
```

所以要记住：

```text
能被看见 != 会被注册成 Bean
注册成 Bean != 立刻创建对象
创建对象 != 一定是原始对象，可能是代理对象
```

## 5. yml 加载关系

Spring Boot 默认加载：

```text
src/main/resources/application.yml
```

如果激活了 profile：

```yml
spring:
  profiles:
    active: dev
```

还会加载：

```text
application-dev.yml
```

在这个项目里：

```text
mall-admin/src/main/resources/application.yml
mall-admin/src/main/resources/application-dev.yml
mall-admin/src/main/resources/application-prod.yml
```

配置优先级大概理解成：

```text
基础 application.yml
被 application-dev.yml 覆盖
被 JVM 参数 / 命令行参数 / 环境变量覆盖
```

`@Value("${redis.key.admin}")` 不是只读 yml，而是读 Spring Environment 里的属性。yml 只是属性来源之一。

## 6. Docker 依赖和应用启动

当前 debug 方式更适合：

```text
Docker 起 MySQL / Redis / MinIO / Mongo / RabbitMQ / Elasticsearch
IDE 本机 debug 启动 Spring Boot 应用
```

`docker-compose.debug.yml` 主要起依赖，不起 Java 应用。

`mall-admin` 本地连接：

```text
MySQL: localhost:3306
Redis: localhost:6379
MinIO: localhost:9000
```

调试时如果数据库、Redis、Mongo 端口被本机服务占用，要先停本机服务或者改 compose 端口。
