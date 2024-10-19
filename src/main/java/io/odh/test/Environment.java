/*
 * Copyright Skodjob authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.odh.test;

import io.odh.test.install.InstallTypes;
import io.skodjob.testframe.utils.LoggerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Class which holds environment variables for system tests.
 */
public class Environment {

    private static final Logger LOGGER = LoggerFactory.getLogger(Environment.class);
    private static final Map<String, String> VALUES = new HashMap<>();
    private static final Map<String, Object> YAML_DATA = loadConfigurationFile();
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    public static final String USER_PATH = System.getProperty("user.dir");

    private static final String CONFIG_FILE_PATH_ENV = "ENV_FILE";
    private static final String PRODUCT_ENV = "PRODUCT";
    private static final String LOG_DIR_ENV = "LOG_DIR";

    /**
     * Install operator odh/rhoai
     */
    private static final String SKIP_INSTALL_OPERATOR_DEPS_ENV = "SKIP_INSTALL_OPERATOR_DEPS";
    private static final String SKIP_INSTALL_OPERATOR_ENV = "SKIP_INSTALL_OPERATOR";
    public static final String SKIP_DEPLOY_DSCI_DSC_ENV = "SKIP_DEPLOY_DSCI_DSC";
    public static final String SKIP_DEPLOY_DSC_DASHBOARD_ENV = "SKIP_DEPLOY_DSC_DASHBOARD";
    public static final String DEFAULT_DSCI_NAME_ENV = "DEFAULT_DSCI_NAME";

    /**
     * Install bundle files
     */
    private static final String INSTALL_FILE_ENV = "INSTALL_FILE";
    private static final String INSTALL_FILE_RELEASED_ENV = "INSTALL_FILE_PREVIOUS";
    private static final String OPERATOR_IMAGE_OVERRIDE_ENV = "OPERATOR_IMAGE_OVERRIDE";

    /**
     * OLM env variables
     */
    private static final String OLM_SOURCE_NAME_ENV = "OLM_SOURCE_NAME";
    private static final String OLM_SOURCE_NAMESPACE_ENV = "OLM_SOURCE_NAMESPACE";
    private static final String OLM_OPERATOR_VERSION_ENV = "OLM_OPERATOR_VERSION";
    private static final String OLM_OPERATOR_NAME_ENV = "OLM_OPERATOR_NAME";
    private static final String OLM_OPERATOR_CHANNEL_ENV = "OLM_OPERATOR_CHANNEL";
    private static final String OPERATOR_INSTALL_TYPE_ENV = "OPERATOR_INSTALL_TYPE";
    private static final String OLM_UPGRADE_STARTING_VERSION_ENV = "OLM_UPGRADE_STARTING_VERSION";

    public static final String PRODUCT_ODH = "odh";
    public static final String PRODUCT_RHOAI = "rhoai";

    /**
     * Set values
     */
    public static final String PRODUCT = getOrDefault(PRODUCT_ENV, PRODUCT_ODH);

    //Install
    public static final boolean SKIP_INSTALL_OPERATOR_DEPS = getOrDefault(SKIP_INSTALL_OPERATOR_DEPS_ENV, Boolean::valueOf, false);
    public static final boolean SKIP_INSTALL_OPERATOR = getOrDefault(SKIP_INSTALL_OPERATOR_ENV, Boolean::valueOf, false);
    public static final boolean SKIP_DEPLOY_DSCI_DSC = getOrDefault(SKIP_DEPLOY_DSCI_DSC_ENV, Boolean::valueOf, false);
    // dashboard is not needed for component api tests but it's useful to enable it for debugging
    public static final boolean SKIP_DEPLOY_DSC_DASHBOARD = getOrDefault(SKIP_DEPLOY_DSC_DASHBOARD_ENV, Boolean::valueOf, true);
    public static final String DEFAULT_DSCI_NAME = getOrDefault(DEFAULT_DSCI_NAME_ENV, String::valueOf, OdhConstants.DEFAULT_DSCI_NAME);

    // YAML Bundle
    public static final String INSTALL_FILE_PATH = getOrDefault(INSTALL_FILE_ENV, TestConstants.LATEST_BUNDLE_DEPLOY_FILE);
    public static final String INSTALL_FILE_PREVIOUS_PATH = getOrDefault(INSTALL_FILE_RELEASED_ENV, TestConstants.RELEASED_BUNDLE_DEPLOY_FILE);
    public static final String OPERATOR_IMAGE_OVERRIDE = getOrDefault(OPERATOR_IMAGE_OVERRIDE_ENV, null);

    // OLM env variables
    public static final String OLM_SOURCE_NAME = getOrDefault(OLM_SOURCE_NAME_ENV, OdhConstants.OLM_SOURCE_NAME);
    public static final String OLM_SOURCE_NAMESPACE = getOrDefault(OLM_SOURCE_NAMESPACE_ENV, "openshift-marketplace");
    public static final String OLM_OPERATOR_CHANNEL = getOrDefault(OLM_OPERATOR_CHANNEL_ENV, OdhConstants.OLM_OPERATOR_CHANNEL);
    public static final String OLM_OPERATOR_VERSION = getOrDefault(OLM_OPERATOR_VERSION_ENV, OdhConstants.OLM_OPERATOR_VERSION);
    public static final String OLM_UPGRADE_STARTING_VERSION = getOrDefault(OLM_UPGRADE_STARTING_VERSION_ENV, OdhConstants.OLM_UPGRADE_STARTING_OPERATOR_VERSION);
    public static final String OLM_OPERATOR_NAME = getOrDefault(OLM_OPERATOR_NAME_ENV, OdhConstants.OLM_OPERATOR_NAME);

    public static final String OPERATOR_INSTALL_TYPE = getOrDefault(OPERATOR_INSTALL_TYPE_ENV, InstallTypes.BUNDLE.toString());

    public static final Path LOG_DIR = getOrDefault(LOG_DIR_ENV, Paths::get, Paths.get(USER_PATH, "target", "logs")).resolve("test-run-" + DATE_FORMAT.format(LocalDateTime.now()));

    private Environment() {
    }

    static {
        String debugFormat = "{}: {}";
        LoggerUtils.logSeparator("-", 30);
        LOGGER.info("Used environment variables:");
        VALUES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!Objects.equals(entry.getValue(), "null")) {
                        LOGGER.info(debugFormat, entry.getKey(), entry.getValue());
                    }
                });
        LoggerUtils.logSeparator("-", 30);
        try {
            saveConfigurationFile();
        } catch (IOException e) {
            LOGGER.warn("Yaml configuration can't be saved");
        }
    }

    public static void print() {
    }

    private static String getOrDefault(String varName, String defaultValue) {
        return getOrDefault(varName, String::toString, defaultValue);
    }

    private static <T> T getOrDefault(String var, Function<String, T> converter, T defaultValue) {
        String value = System.getenv(var) != null ?
                System.getenv(var) :
                (Objects.requireNonNull(YAML_DATA).get(var) != null ?
                        YAML_DATA.get(var).toString() :
                        null);
        T returnValue = defaultValue;
        if (value != null) {
            returnValue = converter.apply(value);
        }
        VALUES.put(var, String.valueOf(returnValue));
        return returnValue;
    }

    private static Map<String, Object> loadConfigurationFile() {
        String config = System.getenv().getOrDefault(CONFIG_FILE_PATH_ENV,
                Paths.get(System.getProperty("user.dir"), "config.yaml").toAbsolutePath().toString());
        Yaml yaml = new Yaml();
        try {
            File yamlFile = new File(config).getAbsoluteFile();
            return yaml.load(new FileInputStream(yamlFile));
        } catch (IOException ex) {
            LOGGER.info("Yaml configuration not provider or not exists");
            return Collections.emptyMap();
        }
    }

    private static void saveConfigurationFile() throws IOException {
        Path logPath = Environment.LOG_DIR;
        Files.createDirectories(logPath);

        LinkedHashMap<String, String> toSave = new LinkedHashMap<>();

        VALUES.forEach((key, value) -> {
            if (isWriteable(key, value)) {
                toSave.put(key, value);
            }
        });

        PrintWriter writer = new PrintWriter(logPath.resolve("config.yaml").toFile());
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        yaml.dump(toSave, writer);
    }

    private static boolean isWriteable(String var, String value) {
        return !value.equals("null")
                && !var.equals(CONFIG_FILE_PATH_ENV)
                && !var.equals(USER_PATH)
                && !var.equals("USER");
    }
}
