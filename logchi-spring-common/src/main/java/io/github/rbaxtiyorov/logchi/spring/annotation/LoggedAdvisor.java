package io.github.rbaxtiyorov.logchi.spring.annotation;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import io.github.rbaxtiyorov.logchi.core.Parse;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.beans.factory.BeanInitializationException;

import java.lang.reflect.Method;

/** Selects {@code @Logged} methods for proxying. A bad {@code slowerThan} fails at startup, not at first call. */
public class LoggedAdvisor extends StaticMethodMatcherPointcutAdvisor {

    public LoggedAdvisor(LogchiSettings.Logged options) {
        setAdvice(new LoggedInterceptor(options));
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        Logged logged = LoggedInterceptor.find(method, targetClass);
        if (logged == null) {
            return false;
        }
        if (!logged.slowerThan().isEmpty()) {
            try {
                Parse.durationMillis(logged.slowerThan());
            } catch (IllegalArgumentException e) {
                throw new BeanInitializationException("Invalid @Logged on " + method + ": " + e.getMessage(), e);
            }
        }
        return true;
    }
}
