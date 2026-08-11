package com.macro.mall.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.NonNull;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logs Spring bean registration and initialization order for local debugging.
 */
@Configuration
@ConditionalOnProperty(prefix = "mall.debug.beans", name = "enabled", havingValue = "true")
public class BeanLifecycleLogger implements BeanFactoryPostProcessor, BeanPostProcessor, PriorityOrdered {

    private static final Logger LOGGER = LoggerFactory.getLogger(BeanLifecycleLogger.class);
    private static final String DEFAULT_PACKAGE_PREFIX = "com.macro.mall";

    private final AtomicInteger initOrder = new AtomicInteger();
    private String packagePrefix = DEFAULT_PACKAGE_PREFIX;

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String configuredPrefix = beanFactory.resolveEmbeddedValue("${mall.debug.beans.package-prefix:" + DEFAULT_PACKAGE_PREFIX + "}");
        if (configuredPrefix != null && !configuredPrefix.isBlank()) {
            packagePrefix = configuredPrefix;
        }
        String[] beanNames = beanFactory.getBeanDefinitionNames();
        int registerOrder = 1;
        LOGGER.info("========== Spring bean definition order, packagePrefix={} ==========", packagePrefix);
        for (String beanName : beanNames) {
            BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
            String beanClassName = beanDefinition.getBeanClassName();
            String factoryBeanName = beanDefinition.getFactoryBeanName();
            if (matches(beanClassName) || matches(factoryBeanName)) {
                LOGGER.info("[bean-definition:{}] name={}, class={}, factoryBean={}, source={}",
                        registerOrder++, beanName, beanClassName, factoryBeanName, beanDefinition.getResourceDescription());
            }
        }
        LOGGER.info("========== End Spring bean definition order ==========");
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        String className = targetClass.getName();
        if (matches(className)) {
            LOGGER.info("[bean-init:{}] name={}, class={}", initOrder.incrementAndGet(), beanName, className);
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private boolean matches(String className) {
        return className != null && className.startsWith(packagePrefix);
    }
}
