package io.github.rbaxtiyorov.logchi.spring;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import io.github.rbaxtiyorov.logchi.spring.annotation.LogInjector;
import io.github.rbaxtiyorov.logchi.spring.annotation.LoggedAdvisor;
import io.github.rbaxtiyorov.logchi.spring.web.MdcTaskDecorator;
import io.github.rbaxtiyorov.logchi.spring.web.ReactiveContextFilter;
import io.github.rbaxtiyorov.logchi.spring.web.ServletContextFilter;
import io.github.rbaxtiyorov.logchi.spring.web.ServletUserFilter;
import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.type.AnnotationMetadata;

@AutoConfiguration
@ConditionalOnProperty(prefix = "logchi", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LogchiProperties.class)
public class LogchiAutoConfiguration {

    @Bean
    static LogInjector logchiLogInjector() {
        return new LogInjector();
    }

    @Bean
    LogchiStartupBanner logchiStartupBanner() {
        return new LogchiStartupBanner();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "logchi.logged", name = "enabled", havingValue = "true", matchIfMissing = true)
    @Import(ProxyRegistrar.class)
    static class LoggedConfiguration {

        /** Static and environment-bound because advisors are created before ordinary beans can be injected. */
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static LoggedAdvisor logchiLoggedAdvisor(Environment environment) {
            LogchiSettings.Logged options = Binder.get(environment).bind("logchi.logged", LogchiSettings.Logged.class)
                    .orElseGet(LogchiSettings.Logged::new);
            return new LoggedAdvisor(options);
        }
    }

    /** Enables proxying the way {@code @Transactional} does, so applications need no AspectJ dependency. */
    static class ProxyRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
            AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "logchi.context", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class ContextConfiguration {

        /** Carries the request context onto {@code @Async} threads. Yields to an application-defined decorator. */
        @Bean
        @ConditionalOnMissingBean(TaskDecorator.class)
        MdcTaskDecorator logchiMdcTaskDecorator() {
            return new MdcTaskDecorator();
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        @ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.web.filter.OncePerRequestFilter"})
        static class Servlet {

            @Bean
            ServletContextFilter logchiServletContextFilter(LogchiProperties properties) {
                return new ServletContextFilter(properties.getContext());
            }

            @Bean
            ServletUserFilter logchiServletUserFilter(LogchiProperties properties) {
                return new ServletUserFilter(properties.getContext());
            }
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
        @ConditionalOnClass(name = "reactor.core.publisher.Mono")
        static class Reactive {

            @Bean
            ReactiveContextFilter logchiReactiveContextFilter(LogchiProperties properties) {
                return new ReactiveContextFilter(properties.getContext());
            }

            @Configuration(proxyBeanMethods = false)
            @ConditionalOnClass(name = "io.micrometer.context.ContextRegistry")
            static class Propagation {
                Propagation() {
                    ContextRegistry.getInstance().registerThreadLocalAccessor(ReactiveContextFilter.TRACE_ID,
                            () -> MDC.get(ReactiveContextFilter.TRACE_ID),
                            value -> MDC.put(ReactiveContextFilter.TRACE_ID, value),
                            () -> MDC.remove(ReactiveContextFilter.TRACE_ID));
                }
            }
        }
    }
}
