package io.github.rbaxtiyorov.logchi.spring.annotation;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import io.github.rbaxtiyorov.logchi.core.Parse;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class LoggedInterceptor implements MethodInterceptor {

    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    /** Remembers the last failure logged on this thread, so one exception is not dumped once per layer. */
    private static final ThreadLocal<WeakReference<Throwable>> LAST_LOGGED = new ThreadLocal<>();

    private record Key(Method method, Class<?> target) {
    }

    private record Plan(Logger logger, String name, Level level, Level errorLevel, long slowMillis, String slowLabel,
                        boolean args, boolean result, int maxLength, String[] parameters, String[] masks) {
    }

    private final Map<Key, Plan> plans = new ConcurrentHashMap<>();
    private final LogchiSettings.Logged options;
    private final Level slowLevel;

    LoggedInterceptor(LogchiSettings.Logged options) {
        this.options = options;
        this.slowLevel = level(options.getSlowLevel());
    }

    /** Method annotation first, then the class annotation, which covers public methods only. */
    static Logged find(Method method, Class<?> target) {
        if (ReflectionUtils.isObjectMethod(method)) {
            return null;
        }
        Method specific = AopUtils.getMostSpecificMethod(method, target);
        Logged onMethod = AnnotatedElementUtils.findMergedAnnotation(specific, Logged.class);
        if (onMethod != null) {
            return onMethod;
        }
        return Modifier.isPublic(specific.getModifiers())
                ? AnnotatedElementUtils.findMergedAnnotation(target, Logged.class)
                : null;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object self = invocation.getThis();
        Class<?> target = self != null ? AopUtils.getTargetClass(self) : invocation.getMethod().getDeclaringClass();
        Plan plan = plans.computeIfAbsent(new Key(invocation.getMethod(), target), this::plan);

        if (plan.logger().isEnabledForLevel(plan.level())) {
            plan.logger().atLevel(plan.level()).log(entryLine(plan, invocation.getArguments()));
        }
        long start = System.nanoTime();
        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable failure) {
            logFailure(plan, failure, millisSince(start));
            throw failure;
        }
        logExit(plan, invocation.getMethod(), result, millisSince(start));
        return result;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private Plan plan(Key key) {
        Method method = key.method();
        Logged logged = find(method, key.target());
        Method specific = AopUtils.getMostSpecificMethod(method, key.target());

        String slowerThan = logged.slowerThan().isEmpty() ? options.getSlowerThan() : logged.slowerThan();
        long slowMillis = slowerThan == null || slowerThan.isEmpty() ? -1 : Parse.durationMillis(slowerThan);
        int maxLength = logged.maxLength() >= 0 ? logged.maxLength() : options.getMaxArgLength();

        Level level = logged.level() == Logged.Level.DEFAULT ? level(options.getLevel()) : level(logged.level().name());
        Level errorLevel = logged.onError() == Logged.Level.DEFAULT
                ? level(options.getErrorLevel()) : level(logged.onError().name());

        String[] names = PARAMETER_NAMES.getParameterNames(specific);
        return new Plan(LoggerFactory.getLogger(key.target()), method.getName(), level, errorLevel, slowMillis,
                slowerThan, logged.args(), logged.result(), maxLength, names, masks(specific));
    }

    private static String[] masks(Method method) {
        Annotation[][] annotations = method.getParameterAnnotations();
        String[] masks = new String[annotations.length];
        for (int i = 0; i < annotations.length; i++) {
            for (Annotation annotation : annotations[i]) {
                if (annotation instanceof Mask mask) {
                    masks[i] = mask.with();
                }
            }
        }
        return masks;
    }

    private static Level level(String name) {
        return Level.valueOf(name.toUpperCase(Locale.ROOT));
    }

    private static String entryLine(Plan plan, Object[] arguments) {
        StringBuilder line = new StringBuilder("→ ").append(plan.name()).append('(');
        if (plan.args()) {
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(plan.parameters() != null ? plan.parameters()[i] : "arg" + i).append('=');
                line.append(plan.masks()[i] != null ? plan.masks()[i] : render(arguments[i], plan.maxLength()));
            }
        }
        return line.append(')').toString();
    }

    private void logExit(Plan plan, Method method, Object result, long millis) {
        boolean slow = plan.slowMillis() >= 0 && millis > plan.slowMillis();
        Level level = slow ? slowLevel : plan.level();
        if (!plan.logger().isEnabledForLevel(level)) {
            return;
        }
        StringBuilder line = new StringBuilder("← ").append(plan.name()).append(' ').append(millis).append("ms");
        if (slow) {
            line.append(" SLOW(>").append(plan.slowLabel()).append(')');
        }
        if (plan.result() && method.getReturnType() != void.class) {
            line.append(' ').append(render(result, plan.maxLength()));
        }
        var event = plan.logger().atLevel(level);
        if (slow) {
            event = event.addKeyValue("durationMs", millis);
        }
        event.log(line.toString());
    }

    private static void logFailure(Plan plan, Throwable failure, long millis) {
        if (!plan.logger().isEnabledForLevel(plan.errorLevel())) {
            return;
        }
        String line = "✗ " + plan.name() + " " + millis + "ms " + failure.getClass().getSimpleName()
                + (failure.getMessage() != null ? ": " + failure.getMessage() : "");
        WeakReference<Throwable> last = LAST_LOGGED.get();
        var event = plan.logger().atLevel(plan.errorLevel()).addKeyValue("durationMs", millis);
        if (last != null && last.get() == failure) {
            event.log(line + " (stack trace logged above)");
            return;
        }
        LAST_LOGGED.set(new WeakReference<>(failure));
        event.setCause(failure).log(line);
    }

    /** Never dumps a large payload, and survives a {@code toString} that throws, as lazy entities can. */
    static String render(Object value, int maxLength) {
        try {
            if (value == null) {
                return "null";
            }
            if (value instanceof Collection<?> collection) {
                return value.getClass().getSimpleName() + "[size=" + collection.size() + "]";
            }
            if (value instanceof Map<?, ?> map) {
                return value.getClass().getSimpleName() + "[size=" + map.size() + "]";
            }
            if (value.getClass().isArray()) {
                return value.getClass().getComponentType().getSimpleName() + "[" + java.lang.reflect.Array.getLength(value) + "]";
            }
            return truncate(String.valueOf(value), maxLength);
        } catch (Exception | LinkageError e) {
            return "<unprintable " + value.getClass().getSimpleName() + ">";
        }
    }

    private static String truncate(String text, int maxLength) {
        if (maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…(+" + (text.length() - maxLength) + ")";
    }
}
