package io.github.rbaxtiyorov.logchi.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a standard SLF4J logger into a field of a Spring bean.
 *
 * <pre>
 * import io.github.rbaxtiyorov.logchi.spring.annotation.Log;
 * import org.slf4j.Logger;
 *
 * &#64;Log private Logger log;
 * </pre>
 *
 * For classes Spring does not manage, use {@code Loggers.get()}. Lombok has an unrelated {@code lombok.extern.java.Log}
 * for classes, so check the import if a field annotation is rejected.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /** Logger name. Defaults to the class declaring the field. */
    String value() default "";
}
