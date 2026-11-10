package io.github.rbaxtiyorov.logchi.spring.annotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fills {@link Log} fields as beans are created. */
public class LogInjector implements BeanPostProcessor {

    private final Map<Class<?>, List<Field>> fieldsByClass = new ConcurrentHashMap<>();

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        for (Field field : fieldsByClass.computeIfAbsent(bean.getClass(), LogInjector::findFields)) {
            Log annotation = field.getAnnotation(Log.class);
            String name = annotation.value().isEmpty() ? field.getDeclaringClass().getName() : annotation.value();
            ReflectionUtils.setField(field, bean, LoggerFactory.getLogger(name));
        }
        return bean;
    }

    private static List<Field> findFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        ReflectionUtils.doWithFields(type, field -> {
            if (field.getType() != Logger.class) {
                throw new BeanInitializationException(
                        "@Log field " + field + " must be of type org.slf4j.Logger");
            }
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                throw new BeanInitializationException("@Log field " + field
                        + " must not be static or final. For a static logger use Loggers.get()");
            }
            ReflectionUtils.makeAccessible(field);
            fields.add(field);
        }, field -> field.isAnnotationPresent(Log.class));
        return fields;
    }
}
