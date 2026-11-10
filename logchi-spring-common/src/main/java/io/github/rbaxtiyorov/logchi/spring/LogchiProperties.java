package io.github.rbaxtiyorov.logchi.spring;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Exposes the core settings as a Spring bean bound to {@code logchi:}. */
@ConfigurationProperties("logchi")
public class LogchiProperties extends LogchiSettings {
}
