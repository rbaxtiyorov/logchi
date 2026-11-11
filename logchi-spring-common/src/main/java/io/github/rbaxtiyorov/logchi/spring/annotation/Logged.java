package io.github.rbaxtiyorov.logchi.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Logs method entry, exit, duration and failures. On a class it applies to every public method.
 *
 * <p>Works through Spring proxies, so it has the same limits as {@code @Transactional}: the class must be
 * a Spring bean and not final, and a call from inside the same object ({@code this.foo()}) is not logged.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Logged {

    /** {@code DEFAULT} means use the value from {@code logchi.logged}. */
    enum Level { DEFAULT, TRACE, DEBUG, INFO, WARN, ERROR }

    /** Level of the normal entry and exit lines. */
    Level level() default Level.DEFAULT;

    /** Escalates the exit line to {@code logchi.logged.slow-level} past this duration, e.g. {@code 500ms}. */
    String slowerThan() default "";

    /** Whether to log arguments. {@code logchi.logged.args=false} turns them off everywhere. */
    boolean args() default true;

    /** Whether to log the return value. {@code logchi.logged.result=false} turns it off everywhere. */
    boolean result() default true;

    /** Longest rendered value. -1 means use {@code logchi.logged.max-arg-length}. */
    int maxLength() default -1;

    /**
     * Exceptions that are an expected outcome, such as "not found". They are logged at {@link #level()}
     * without a stack trace. Adds to {@code logchi.logged.quiet-exceptions}.
     */
    Class<? extends Throwable>[] quietOn() default {};

    /** Level of the line logged when the method throws. */
    Level onError() default Level.DEFAULT;
}
