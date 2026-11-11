package io.github.rbaxtiyorov.logchi.spring;

import io.github.rbaxtiyorov.logchi.spring.annotation.LogInjector;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Makes {@code @Log} work in every Spring Boot context. Auto-configuration would not do: test slices such
 * as {@code @WebMvcTest} leave it out, and a disabled logchi must not leave those fields null either.
 */
public class LogchiContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String BEAN_NAME = "logchiLogInjector";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (context.getBeanFactory() instanceof BeanDefinitionRegistry registry
                && !registry.containsBeanDefinition(BEAN_NAME)) {
            RootBeanDefinition definition = new RootBeanDefinition(LogInjector.class);
            definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            registry.registerBeanDefinition(BEAN_NAME, definition);
        }
    }
}
