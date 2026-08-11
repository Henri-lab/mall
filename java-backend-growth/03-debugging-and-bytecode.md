# 03. Debugging And Bytecode

## 1. 调试器不是按源码执行

Java 调试器最终依据的是：

```text
字节码指令
LineNumberTable
LocalVariableTable
```

源码里有一行，不代表字节码里有可停的指令。

例子：

```java
public int create(PmsProductParam productParam) {
    int count;
    PmsProduct product = productParam;
}
```

`int count;` 只是局部变量声明，没有赋值，通常不会产生字节码指令。

所以这一行断点可能无效。下一行：

```java
PmsProduct product = productParam;
```

有实际赋值，断点会稳定得多。

经验：

```text
断点尽量打在有实际执行动作的语句上
```

比如：

```java
product.setId(null);
productMapper.insertSelective(product);
return count;
```

不要依赖：

```java
注释
空行
纯变量声明
方法声明行
注解行
```

## 2. javap 是排查断点问题的利器

使用：

```bash
javap -c -l com.macro.mall.service.impl.PmsProductServiceImpl
```

重点看：

```text
LineNumberTable
```

如果某行没有出现在 LineNumberTable 中，或者没有对应字节码 offset，断点就不可靠。

我们已经在项目里加了任务：

```text
.vscode/tasks.json
scripts/javap-current.sh
```

使用：

```text
Cmd + Shift + P
Tasks: Run Task
javap current Java file
```

它会自动：

```text
解析当前 Java 文件 package
解析 class name
找到 Maven module
mvn compile
拼 target/classes classpath
javap -c -l
```

不用手写类路径。

## 3. VS Code Java 调试常见问题

### 断点空心 / 不命中

常见原因：

```text
源码和 target/classes 不同步
Java Language Server 缓存脏
断点打在无字节码行
运行的不是当前模块
实际请求没有到该方法
```

处理顺序：

```bash
mvn -q -pl mall-admin -am -DskipTests compile
```

然后在 VS Code 执行：

```text
Java: Clean Java Language Server Workspace
Reload Window
```

### Build failed, do you want to continue?

这个是 VS Code Java Debugger 启动前自动 build 失败，不一定是 Maven 失败。

如果命令行 Maven 能过：

```bash
mvn -q -DskipTests compile
```

但 VS Code 仍提示，多半是 Java Language Server 工程模型或缓存问题。

处理：

```text
Java: Clean Java Language Server Workspace
Reload Window
```

临时绕过：

```json
"java.debug.settings.forceBuildBeforeLaunch": false
```

但不建议长期靠绕过。

## 4. 代理导致 Step Into 进入 Spring 源码

如果 Service 有事务：

```java
@Transactional
int create(PmsProductParam productParam);
```

你从 Controller Step Into：

```java
productService.create(productParam)
```

可能会先进入：

```text
CglibAopProxy
TransactionInterceptor
ReflectiveMethodInvocation
```

这是正常的。

真实链路：

```text
Controller
 -> 代理对象
 -> 事务拦截器
 -> 实现类方法
```

如果想确认代理对象：

```java
productService.getClass()
```

如果想确认目标类：

```java
AopUtils.getTargetClass(productService)
```

## 5. 函数断点

有时不想手动翻源码，可以使用 Function Breakpoint。

例如想断在 `@Value` 处理逻辑：

```text
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.postProcessProperties
```

更接近字段注入：

```text
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor$AutowiredFieldElement.inject
```

内部类用 `$`，不是点号。

## 6. 好的 debug 习惯

1. 先确认请求是否到 Controller。
2. Controller 到了，再看注入对象是不是代理。
3. Service 不进，先看断点行有没有字节码。
4. Mapper 不进，确认是否 MyBatis 接口动态代理。
5. SQL 不符合预期，打开 MyBatis SQL 日志或直接看 XML。
6. 配置不生效，先看 active profile，再看配置优先级。
7. 启动报错，先区分扫描阶段、BeanDefinition 阶段、创建 Bean 阶段、初始化阶段。

## 7. 字节码和源码思维

你需要慢慢建立这种意识：

```text
源码是给人看的
字节码是 JVM 执行的
调试器靠调试信息把两者映射起来
```

所以：

```text
源码行有，不代表能断
源码和 class 不一致，断点会错位
代理对象运行时生成，源码里没有
MyBatis Mapper 实现运行时生成，源码里也没有
```

企业项目里很多困惑来自“源码视角”和“运行时视角”没分开。
