package io.github.rbaxtiyorov.logchi.spring;

import ch.qos.logback.classic.LoggerContext;
import io.github.rbaxtiyorov.logchi.core.LogchiInstaller;
import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Installs logchi right after Spring Boot initializes logging and before any bean exists, so startup
 * logs reach the files too. Auto-configuration would run far too late for that.
 */
public class LogchiBootstrapListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        LogchiSettings settings = Binder.get(environment).bind("logchi", LogchiSettings.class)
                .orElseGet(LogchiSettings::new);
        if (settings.getAppName() == null || settings.getAppName().isBlank()) {
            settings.setAppName(environment.getProperty("spring.application.name", "app"));
        }
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            LogchiInstaller.install(context, settings);
        } else {
            System.err.println("logchi: disabled, Logback is not the active SLF4J provider (found "
                    + factory.getClass().getName() + ")");
        }
    }
}
