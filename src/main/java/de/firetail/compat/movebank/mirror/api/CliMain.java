package de.firetail.compat.movebank.mirror.api;

import org.springframework.boot.SpringApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "movebank-mirror-api",
        mixinStandardHelpOptions = true,
        version = "movebank-mirror-api 0.0.1",
        description = "Read-only Movebank REST API replay backed by a local movebank-mirror directory."
)
public class CliMain implements Callable<Integer> {

    @Option(names = {"-d", "--mirror-dir"}, paramLabel = "DIR",
            defaultValue = "${env:MOVEBANK_MIRROR_DIR}",
            description = "Local movebank-mirror directory to serve from "
                    + "(env: MOVEBANK_MIRROR_DIR).")
    String mirrorDir;

    @Option(names = {"-p", "--port"}, paramLabel = "PORT",
            defaultValue = "${env:MOVEBANK_MIRROR_API_PORT:-8080}",
            description = "TCP port to listen on (env: MOVEBANK_MIRROR_API_PORT, default: ${DEFAULT-VALUE}).")
    int port;

    @Option(names = {"-b", "--bind"}, paramLabel = "ADDRESS",
            defaultValue = "${env:MOVEBANK_MIRROR_API_BIND:-127.0.0.1}",
            description = "Bind address (env: MOVEBANK_MIRROR_API_BIND, default: ${DEFAULT-VALUE}). "
                    + "Use 0.0.0.0 to expose on all interfaces — there is no authentication, "
                    + "so do not bind publicly without a reverse proxy that adds it.")
    String bind;

    @Option(names = {"-v", "--verbose"},
            description = "Increase log verbosity. -v = debug, -vv = trace.")
    boolean[] verbosity = new boolean[0];

    @Option(names = {"-q", "--quiet"},
            description = "Reduce log verbosity to warnings only.")
    boolean quiet;

    @Override
    public Integer call() {
        if (mirrorDir == null || mirrorDir.isBlank()) {
            System.err.println("error: --mirror-dir (or env MOVEBANK_MIRROR_DIR) is required");
            return 2;
        }
        File baseDir = new File(mirrorDir);
        if (!baseDir.isDirectory()) {
            System.err.println("error: --mirror-dir is not a directory: " + baseDir);
            return 5;
        }

        applyLogLevel();

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("movebank.mirror.base-dir", baseDir.getAbsolutePath());
        defaults.put("server.port", port);
        defaults.put("server.address", bind);

        SpringApplication app = new SpringApplication(MovebankMirrorApiApplication.class);
        app.setDefaultProperties(defaults);
        app.run();
        return 0;
    }

    private void applyLogLevel() {
        // Maps -v/-q onto Spring Boot's root log level (also covers slf4j).
        String level;
        if (quiet) {
            level = "WARN";
        } else if (verbosity.length >= 2) {
            level = "TRACE";
        } else if (verbosity.length == 1) {
            level = "DEBUG";
        } else {
            level = "INFO";
        }
        System.setProperty("logging.level.root", level);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new CliMain()).execute(args);
        // For exit==0 we either booted Spring (Tomcat keeps the JVM alive on its
        // own non-daemon threads — do NOT System.exit, that would tear them
        // down) or printed --help/--version (no live threads, JVM exits on its
        // own). For non-zero, surface the code to the shell.
        if (exit != 0) {
            System.exit(exit);
        }
    }
}
