package io.github.rbaxtiyorov.logchi.spring;

import io.github.rbaxtiyorov.logchi.core.LogchiInstaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/** Logs one line saying where the logs are going and how much disk they use. */
public class LogchiStartupBanner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger("logchi");

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        LogchiInstaller.summary().ifPresent(log::info);
    }
}
