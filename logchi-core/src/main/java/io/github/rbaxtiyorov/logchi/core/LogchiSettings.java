package io.github.rbaxtiyorov.logchi.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything configurable under {@code logchi:}. Plain JavaBean so core stays free of Spring. */
public class LogchiSettings {

    public enum Format { TEXT, JSON }

    public enum JsonFlavor { ECS, LOGSTASH, COMPACT }

    public enum Period { HOURLY, DAILY, NONE }

    public enum Scope { OWN, SHARED }

    public enum Color { AUTO, ALWAYS, NEVER }

    private boolean enabled = true;
    private String dir = "./logs";
    private String appName = "";
    private String instanceId = "";
    private int stackTraceLines = 30;
    private Console console = new Console();
    private File file = new File();
    private Map<String, Sink> levels = defaultLevels();
    private Map<String, Sink> channels = new LinkedHashMap<>();
    private Mask mask = new Mask();
    private Context context = new Context();
    private Logged logged = new Logged();

    private static Map<String, Sink> defaultLevels() {
        Sink error = new Sink();
        error.setCold("90d");
        Map<String, Sink> levels = new LinkedHashMap<>();
        levels.put("error", error);
        return levels;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public int getStackTraceLines() { return stackTraceLines; }
    public void setStackTraceLines(int stackTraceLines) { this.stackTraceLines = stackTraceLines; }
    public Console getConsole() { return console; }
    public void setConsole(Console console) { this.console = console; }
    public File getFile() { return file; }
    public void setFile(File file) { this.file = file; }
    public Map<String, Sink> getLevels() { return levels; }
    public void setLevels(Map<String, Sink> levels) { this.levels = levels; }
    public Map<String, Sink> getChannels() { return channels; }
    public void setChannels(Map<String, Sink> channels) { this.channels = channels; }
    public Mask getMask() { return mask; }
    public void setMask(Mask mask) { this.mask = mask; }
    public Context getContext() { return context; }
    public void setContext(Context context) { this.context = context; }
    public Logged getLogged() { return logged; }
    public void setLogged(Logged logged) { this.logged = logged; }

    public static class Console {
        private boolean enabled = true;
        private Format format = Format.TEXT;
        private Color color = Color.AUTO;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Format getFormat() { return format; }
        public void setFormat(Format format) { this.format = format; }
        public Color getColor() { return color; }
        public void setColor(Color color) { this.color = color; }
    }

    public static class File {
        private boolean enabled = true;
        private Format format = Format.TEXT;
        private JsonFlavor jsonFlavor = JsonFlavor.ECS;
        private String extension = "txt";
        private Rotation rotation = new Rotation();
        private Retention retention = new Retention();
        private Async async = new Async();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Format getFormat() { return format; }
        public void setFormat(Format format) { this.format = format; }
        public JsonFlavor getJsonFlavor() { return jsonFlavor; }
        public void setJsonFlavor(JsonFlavor jsonFlavor) { this.jsonFlavor = jsonFlavor; }
        public String getExtension() { return extension; }
        public void setExtension(String extension) { this.extension = extension; }
        public Rotation getRotation() { return rotation; }
        public void setRotation(Rotation rotation) { this.rotation = rotation; }
        public Retention getRetention() { return retention; }
        public void setRetention(Retention retention) { this.retention = retention; }
        public Async getAsync() { return async; }
        public void setAsync(Async async) { this.async = async; }
    }

    public static class Rotation {
        private Period period = Period.DAILY;
        private String maxSize = "100MB";

        public Period getPeriod() { return period; }
        public void setPeriod(Period period) { this.period = period; }
        public String getMaxSize() { return maxSize; }
        public void setMaxSize(String maxSize) { this.maxSize = maxSize; }
    }

    public static class Retention {
        private String hot = "1d";
        private String cold = "30d";
        private boolean compress = true;
        private int compressLevel = 6;
        private String maxTotal = "5GB";
        private String minFreeDisk = "1GB";
        private Scope scope = Scope.SHARED;
        private String checkInterval = "5m";

        public String getHot() { return hot; }
        public void setHot(String hot) { this.hot = hot; }
        public String getCold() { return cold; }
        public void setCold(String cold) { this.cold = cold; }
        public boolean isCompress() { return compress; }
        public void setCompress(boolean compress) { this.compress = compress; }
        public int getCompressLevel() { return compressLevel; }
        public void setCompressLevel(int compressLevel) { this.compressLevel = compressLevel; }
        public String getMaxTotal() { return maxTotal; }
        public void setMaxTotal(String maxTotal) { this.maxTotal = maxTotal; }
        public String getMinFreeDisk() { return minFreeDisk; }
        public void setMinFreeDisk(String minFreeDisk) { this.minFreeDisk = minFreeDisk; }
        public Scope getScope() { return scope; }
        public void setScope(Scope scope) { this.scope = scope; }
        public String getCheckInterval() { return checkInterval; }
        public void setCheckInterval(String checkInterval) { this.checkInterval = checkInterval; }
    }

    public static class Async {
        private boolean enabled = true;
        private int queueSize = 8192;
        private String dropBelow = "INFO";
        private String flushTimeout = "5s";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getQueueSize() { return queueSize; }
        public void setQueueSize(int queueSize) { this.queueSize = queueSize; }
        public String getDropBelow() { return dropBelow; }
        public void setDropBelow(String dropBelow) { this.dropBelow = dropBelow; }
        public String getFlushTimeout() { return flushTimeout; }
        public void setFlushTimeout(String flushTimeout) { this.flushTimeout = flushTimeout; }
    }

    /** Per-level or per-channel override. A null field inherits from {@code file}. */
    public static class Sink {
        private boolean enabled = true;
        private String hot;
        private String cold;
        private Format format;
        private Boolean async;
        private boolean additive = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHot() { return hot; }
        public void setHot(String hot) { this.hot = hot; }
        public String getCold() { return cold; }
        public void setCold(String cold) { this.cold = cold; }
        public Format getFormat() { return format; }
        public void setFormat(Format format) { this.format = format; }
        public Boolean getAsync() { return async; }
        public void setAsync(Boolean async) { this.async = async; }
        public boolean isAdditive() { return additive; }
        public void setAdditive(boolean additive) { this.additive = additive; }
    }

    public static class Mask {
        private boolean enabled = true;
        private boolean useDefaults = true;
        private boolean cards = true;
        private List<String> keys = new ArrayList<>();
        private List<String> patterns = new ArrayList<>();
        private String replacement = "***";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isUseDefaults() { return useDefaults; }
        public void setUseDefaults(boolean useDefaults) { this.useDefaults = useDefaults; }
        public boolean isCards() { return cards; }
        public void setCards(boolean cards) { this.cards = cards; }
        public List<String> getKeys() { return keys; }
        public void setKeys(List<String> keys) { this.keys = keys; }
        public List<String> getPatterns() { return patterns; }
        public void setPatterns(List<String> patterns) { this.patterns = patterns; }
        public String getReplacement() { return replacement; }
        public void setReplacement(String replacement) { this.replacement = replacement; }
    }

    public static class Context {
        private boolean enabled = true;
        private boolean traceId = true;
        private String header = "X-Request-Id";
        private String userId = "principal";
        private List<String> extraHeaders = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isTraceId() { return traceId; }
        public void setTraceId(boolean traceId) { this.traceId = traceId; }
        public String getHeader() { return header; }
        public void setHeader(String header) { this.header = header; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public List<String> getExtraHeaders() { return extraHeaders; }
        public void setExtraHeaders(List<String> extraHeaders) { this.extraHeaders = extraHeaders; }
    }

    public static class Logged {
        private boolean enabled = true;
        private String level = "DEBUG";
        private String slowLevel = "WARN";
        private String errorLevel = "ERROR";
        private String slowerThan = "";
        private int maxArgLength = 500;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getSlowLevel() { return slowLevel; }
        public void setSlowLevel(String slowLevel) { this.slowLevel = slowLevel; }
        public String getErrorLevel() { return errorLevel; }
        public void setErrorLevel(String errorLevel) { this.errorLevel = errorLevel; }
        public String getSlowerThan() { return slowerThan; }
        public void setSlowerThan(String slowerThan) { this.slowerThan = slowerThan; }
        public int getMaxArgLength() { return maxArgLength; }
        public void setMaxArgLength(int maxArgLength) { this.maxArgLength = maxArgLength; }
    }
}
