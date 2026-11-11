package io.github.rbaxtiyorov.logchi.spring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.rbaxtiyorov.logchi.spring.annotation.Log;
import io.github.rbaxtiyorov.logchi.spring.annotation.Logged;
import io.github.rbaxtiyorov.logchi.spring.annotation.Mask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggedAndInjectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new LogchiContextInitializer())
            .withConfiguration(AutoConfigurations.of(LogchiAutoConfiguration.class))
            .withUserConfiguration(Beans.class);

    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(Service.class);
        logger.setLevel(Level.DEBUG);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void release() {
        logger.detachAppender(events);
        logger.setLevel(null);
    }

    private List<String> messages() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void injectsLoggersIntoFieldsIncludingInherited() {
        runner.run(context -> {
            Service service = context.getBean(Service.class);
            assertNotNull(service.injected());
            assertEquals(Service.class.getName(), service.injected().getName());
            assertEquals("custom.name", service.named().getName());
            assertEquals(Base.class.getName(), service.inherited().getName());
        });
    }

    @Test
    void wrongFieldTypeFailsAtStartup() {
        runner.withUserConfiguration(BadFieldBeans.class).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains("must be of type org.slf4j.Logger")
                    || String.valueOf(context.getStartupFailure().getCause()).contains("must be of type org.slf4j.Logger"));
        });
    }

    @Test
    void logsEntryAndExitWithArgumentsAndResult() {
        runner.run(context -> {
            assertEquals("hello bob", context.getBean(Service.class).greet("bob"));
            assertEquals(List.of("→ greet(name=bob)"), messages().subList(0, 1));
            assertTrue(messages().get(1).matches("← greet \\d+ms hello bob"), messages().toString());
            assertEquals(Level.DEBUG, events.list.get(1).getLevel());
        });
    }

    @Test
    void slowCallEscalatesToWarnWithDuration() {
        runner.run(context -> {
            context.getBean(Service.class).slow();
            ILoggingEvent exit = events.list.get(1);
            assertEquals(Level.WARN, exit.getLevel());
            assertTrue(exit.getFormattedMessage().contains("SLOW(>10ms)"), exit.getFormattedMessage());
            assertEquals("durationMs", exit.getKeyValuePairs().get(0).key);
        });
    }

    @Test
    void fastCallStaysAtNormalLevelWithoutDuration() {
        runner.run(context -> {
            context.getBean(Service.class).fast();
            assertEquals(Level.DEBUG, events.list.get(1).getLevel());
            assertNull(events.list.get(1).getKeyValuePairs());
        });
    }

    @Test
    void yamlSlowerThanIsTheDefault() {
        runner.withPropertyValues("logchi.logged.slower-than=1ms").run(context -> {
            context.getBean(Service.class).greetSlowly();
            assertEquals(Level.WARN, events.list.get(1).getLevel());
            assertTrue(events.list.get(1).getFormattedMessage().contains("SLOW(>1ms)"));
        });
    }

    @Test
    void slowLinesSurfaceEvenWhenDebugIsOff() {
        logger.setLevel(Level.INFO);
        runner.run(context -> {
            context.getBean(Service.class).slow();
            assertEquals(1, events.list.size(), messages().toString());
            assertEquals(Level.WARN, events.list.get(0).getLevel());
        });
    }

    @Test
    void masksAnnotatedParameters() {
        runner.run(context -> {
            context.getBean(Service.class).pay("4111", "bob");
            assertEquals("→ pay(card=***, name=bob)", messages().get(0));
        });
    }

    @Test
    void failuresAreLoggedRethrownAndCarryTheStack() {
        runner.run(context -> {
            Service service = context.getBean(Service.class);
            IllegalStateException e = assertThrows(IllegalStateException.class, service::fail);
            assertEquals("boom", e.getMessage());
            ILoggingEvent failure = events.list.get(1);
            assertEquals(Level.ERROR, failure.getLevel());
            assertTrue(failure.getFormattedMessage().matches("✗ fail \\d+ms IllegalStateException: boom"),
                    failure.getFormattedMessage());
            assertNotNull(failure.getThrowableProxy());
        });
    }

    @Test
    void nestedLayersDoNotRepeatTheStack() {
        runner.run(context -> {
            Service service = context.getBean(Service.class);
            assertThrows(IllegalStateException.class, service::failThroughOuter);
            List<ILoggingEvent> failures = events.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertEquals(2, failures.size());
            assertNotNull(failures.get(0).getThrowableProxy());
            assertNull(failures.get(1).getThrowableProxy());
            assertTrue(failures.get(1).getFormattedMessage().contains("logged above"));
        });
    }

    @Test
    void largeValuesAreSummarized() {
        runner.run(context -> {
            context.getBean(Service.class).bulk(List.of(1, 2, 3), new byte[2048], "x".repeat(600));
            String entry = messages().get(0);
            assertTrue(entry.contains("[size=3]"), entry);
            assertTrue(entry.contains("byte[2048]"), entry);
            assertTrue(entry.contains("…(+100)"), entry);
        });
    }

    @Test
    void throwingToStringNeverBreaksTheCall() {
        runner.run(context -> {
            assertEquals("ok", context.getBean(Service.class).poison(new Poison()));
            assertTrue(messages().get(0).contains("<unprintable Poison>"), messages().get(0));
        });
    }

    @Test
    void classLevelCoversPublicMethodsButNotObjectMethods() {
        Logger classLogger = (Logger) LoggerFactory.getLogger(ClassLevel.class);
        classLogger.setLevel(Level.DEBUG);
        classLogger.addAppender(events);
        try {
            runner.run(context -> {
                ClassLevel bean = context.getBean(ClassLevel.class);
                assertTrue(AopUtils.isAopProxy(bean));
                bean.work();
                bean.toString();
                assertEquals(2, events.list.size(), messages().toString());
                assertEquals("→ work()", messages().get(0));
            });
        } finally {
            classLogger.detachAppender(events);
            classLogger.setLevel(null);
        }
    }

    @Test
    void annotationOverridesConfiguredLevelAndHidesResult() {
        runner.run(context -> {
            context.getBean(Service.class).audited();
            assertEquals(Level.INFO, events.list.get(0).getLevel());
            assertFalse(messages().get(1).contains("secret-result"), messages().get(1));
        });
    }

    @Test
    void invalidSlowerThanFailsAtStartup() {
        runner.withUserConfiguration(BadLoggedBeans.class).run(context -> {
            assertNotNull(context.getStartupFailure());
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root.getMessage().contains("soonish"), root.getMessage());
        });
    }

    @Test
    void loggedCanBeTurnedOff() {
        runner.withPropertyValues("logchi.logged.enabled=false").run(context -> {
            assertFalse(AopUtils.isAopProxy(context.getBean(Service.class)));
            context.getBean(Service.class).greet("bob");
            assertTrue(events.list.isEmpty());
        });
    }

    @Test
    void wholeLibraryCanBeTurnedOff() {
        runner.withPropertyValues("logchi.enabled=false").run(context -> {
            assertFalse(context.containsBean("logchiLoggedAdvisor"));
            assertFalse(context.containsBean("logchiStartupBanner"));
            assertFalse(AopUtils.isAopProxy(context.getBean(Service.class)));
        });
    }

    /** Otherwise switching logchi off would turn every log call on such a field into a NullPointerException. */
    @Test
    void logFieldsAreInjectedEvenWhenTheLibraryIsOff() {
        runner.withPropertyValues("logchi.enabled=false").run(context ->
                assertSame(logger, context.getBean(Service.class).injected()));
    }

    @Test
    void aMisspelledLevelStopsStartup() {
        runner.withPropertyValues("logchi.logged.level=LOUD").run(context -> {
            assertNotNull(context.getStartupFailure());
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root.getMessage().contains("logchi.logged.level 'LOUD'"), root.toString());
        });
    }

    @Test
    void injectedLoggerIsSharedWithStandardLookup() {
        runner.run(context -> assertSame(logger, context.getBean(Service.class).injected()));
    }

    static class Poison {
        @Override
        public String toString() {
            throw new IllegalStateException("lazy load failed");
        }
    }

    static class Base {
        @Log
        org.slf4j.Logger baseLog;
    }

    static class Service extends Base {
        @Log
        private org.slf4j.Logger log;

        @Log("custom.name")
        private org.slf4j.Logger namedLog;

        Service self;

        org.slf4j.Logger injected() { return log; }
        org.slf4j.Logger named() { return namedLog; }
        org.slf4j.Logger inherited() { return baseLog; }

        @Logged
        public String greet(String name) { return "hello " + name; }

        @Logged(slowerThan = "10ms")
        public void slow() throws InterruptedException { Thread.sleep(30); }

        @Logged(slowerThan = "1m")
        public void fast() { }

        @Logged
        public void greetSlowly() throws InterruptedException { Thread.sleep(20); }

        @Logged
        public void pay(@Mask String card, String name) { }

        @Logged
        public void fail() { throw new IllegalStateException("boom"); }

        @Logged
        public void failThroughOuter() { self.fail(); }

        @Logged
        public void bulk(List<Integer> items, byte[] data, String text) { }

        @Logged
        public String poison(Poison value) { return "ok"; }

        @Logged(level = Logged.Level.INFO, result = false)
        public String audited() { return "secret-result"; }
    }

    @Logged
    static class ClassLevel {
        public void work() { }
    }

    static class BadField {
        @Log
        private String log;
    }

    static class BadLogged {
        @Logged(slowerThan = "soonish")
        public void work() { }
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        @Bean
        Service service() { return new Service(); }

        @Bean
        ClassLevel classLevel() { return new ClassLevel(); }

        @Bean
        static org.springframework.beans.factory.SmartInitializingSingleton wire(
                org.springframework.beans.factory.ObjectProvider<Service> provider) {
            return () -> {
                Service proxy = provider.getObject();
                Service target = proxy;
                if (AopUtils.isAopProxy(proxy)) {
                    try {
                        target = (Service) ((org.springframework.aop.framework.Advised) proxy).getTargetSource().getTarget();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                target.self = proxy;
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BadFieldBeans {
        @Bean
        BadField badField() { return new BadField(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class BadLoggedBeans {
        @Bean
        BadLogged badLogged() { return new BadLogged(); }
    }

    /** As in a test slice such as @WebMvcTest, where logchi's auto-configuration is not loaded. */
    @Test
    void logFieldsAreInjectedWithoutTheAutoConfiguration() {
        new ApplicationContextRunner().withInitializer(new LogchiContextInitializer())
                .withUserConfiguration(Beans.class)
                .run(context -> assertSame(logger, context.getBean(Service.class).injected()));
    }
}
