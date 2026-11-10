package io.github.rbaxtiyorov.logchi.core.instance;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Picks a stable identity for this process so instances never write to the same file. */
public final class InstanceId {

    private InstanceId() {
    }

    /** Configured value, else {@code HOSTNAME} (the pod name in Kubernetes), else the machine name. */
    public static String resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return sanitize(configured);
        }
        for (String candidate : List.of(env("HOSTNAME"), env("COMPUTERNAME"), etcHostname(), inetHostname())) {
            if (!candidate.isBlank()) {
                return sanitize(candidate);
            }
        }
        return "pid" + pid();
    }

    /** Restricts to characters that cannot break the dot-separated file name format. */
    public static String sanitize(String value) {
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public static long pid() {
        return ProcessHandle.current().pid();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static String etcHostname() {
        try {
            Path path = Path.of("/etc/hostname");
            return Files.isReadable(path) ? Files.readString(path).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String inetHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }
}
