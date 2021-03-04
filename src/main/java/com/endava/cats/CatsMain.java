package com.endava.cats;

import ch.qos.logback.classic.Level;
import com.endava.cats.args.*;
import com.endava.cats.fuzzer.*;
import com.endava.cats.fuzzer.fields.CustomFuzzer;
import com.endava.cats.model.FuzzingData;
import com.endava.cats.model.factory.FuzzingDataFactory;
import com.endava.cats.report.ExecutionStatisticsListener;
import com.endava.cats.report.TestCaseListener;
import com.endava.cats.util.CatsUtil;
import io.github.ludovicianul.prettylogger.PrettyLogger;
import io.github.ludovicianul.prettylogger.PrettyLoggerFactory;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.apache.commons.io.Charsets;
import org.fusesource.jansi.Ansi;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * We need to print the build version and build time
 */
@ConditionalOnResource(
        resources = {"${spring.info.build.location:classpath:META-INF/build-info.properties}"}
)
@SpringBootApplication
public class CatsMain implements CommandLineRunner, ExitCodeGenerator {
    public static final AtomicInteger TEST = new AtomicInteger(0);
    public static final String EMPTY = "empty";
    public static final String ALL = "all";
    private static final String LIST = "list";
    private static final PrettyLogger LOGGER = PrettyLoggerFactory.getLogger(CatsMain.class);
    private static final String PATHS_STRING = "paths";
    private static final String FUZZERS_STRING = "fuzzers";
    private static final String FIELDS_FUZZER_STRATEGIES = "fieldsFuzzerStrategies";
    private static final String HELP = "help";
    private static final String VERSION = "version";
    private static final String EXAMPLE = ansi().fg(Ansi.Color.CYAN).a("./cats.jar --server=http://localhost:8080 --contract=con.yml").reset().toString();
    private static final String COMMAND_TEMPLATE = ansi().render("\t --@|cyan {}|@={}").reset().toString();

    @Value("${contract:empty}")
    private String contract;
    @Value("${server:empty}")
    private String server;

    @Autowired
    private ProcessingArguments processingArguments;
    @Autowired
    private FilterArguments filterArguments;
    @Autowired
    private FilesArguments filesArguments;
    @Autowired
    private ReportingArguments reportingArguments;
    @Autowired
    private CheckArguments checkArgs;
    @Autowired
    private AuthArguments authArgs;

    @Autowired
    private List<Fuzzer> fuzzers;
    @Autowired
    private FuzzingDataFactory fuzzingDataFactory;
    @Autowired
    private CustomFuzzer customFuzzer;
    @Autowired
    private ExecutionStatisticsListener executionStatisticsListener;
    @Autowired
    private TestCaseListener testCaseListener;

    public static void main(String... args) {
        System.exit(SpringApplication.exit(new SpringApplicationBuilder(CatsMain.class).bannerMode(Banner.Mode.CONSOLE).logStartupInfo(false).build().run(args)));
    }

    public static Map<String, Schema> getSchemas(OpenAPI openAPI) {
        Map<String, Schema> schemas = openAPI.getComponents().getSchemas();

        Optional.ofNullable(openAPI.getComponents().getRequestBodies())
                .orElseGet(Collections::emptyMap)
                .forEach((key, value) -> addToSchemas(schemas, key, value.get$ref(), value.getContent()));

        Optional.ofNullable(openAPI.getComponents().getResponses())
                .orElseGet(Collections::emptyMap)
                .forEach((key, value) -> addToSchemas(schemas, key, value.get$ref(), value.getContent()));

        return schemas;
    }

    private static void addToSchemas(Map<String, Schema> schemas, String schemaName, String ref, Content content) {
        Schema schemaToAdd;
        if (ref == null && content != null) {
            Schema refSchema = content.get(MimeTypeUtils.APPLICATION_JSON_VALUE).getSchema();

            if (refSchema instanceof ArraySchema) {
                ref = ((ArraySchema) refSchema).getItems().get$ref();
                refSchema.set$ref(ref);
                schemaToAdd = refSchema;
            } else {
                ref = refSchema.get$ref();
                String schemaKey = ref.substring(ref.lastIndexOf('/') + 1);
                schemaToAdd = schemas.get(schemaKey);
            }
            schemas.put(schemaName, schemaToAdd);
        } else if (ref == null) {
            schemas.put(schemaName, new Schema());
        }
    }

    @Override
    public void run(String... args) {
        try {
            testCaseListener.startSession();
            this.doLogic(args);
            testCaseListener.endSession();
        } catch (StopExecutionException e) {
            LOGGER.debug("StopExecution: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Something went wrong while running CATS!", e);
        }
    }

    public void doLogic(String... args) throws IOException {
        this.sortFuzzersByName();
        this.processArgs(args);
        this.printArgs();

        OpenAPI openAPI = this.createOpenAPI();
        this.processContractDependentCommands(openAPI, args);

        List<String> suppliedPaths = this.matchSuppliedPathsWithContractPaths(openAPI);
        filesArguments.loadConfig();
        filterArguments.loadConfig(args);
        this.startFuzzing(openAPI, suppliedPaths);
        this.executeCustomFuzzer();
    }


    public void startFuzzing(OpenAPI openAPI, List<String> suppliedPaths) {
        for (Map.Entry<String, PathItem> entry : this.sortPathsAlphabetically(openAPI)) {

            if (suppliedPaths.contains(entry.getKey())) {
                this.fuzzPath(entry, openAPI);
            } else {
                LOGGER.skip("Skipping path {}", entry.getKey());
            }
        }
    }

    private LinkedHashSet<Map.Entry<String, PathItem>> sortPathsAlphabetically(OpenAPI openAPI) {
        return openAPI.getPaths().entrySet()
                .stream().sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void executeCustomFuzzer() {
        customFuzzer.executeCustomFuzzerTests();
    }

    private void sortFuzzersByName() {
        fuzzers.sort(Comparator.comparing(fuzzer -> fuzzer.getClass().getSimpleName()));
    }

    /**
     * Check if there are any supplied paths and match them against the contract
     *
     * @param openAPI the OpenAPI object parsed from the contract
     * @return the list of paths from the contract matching the supplied list
     */
    private List<String> matchSuppliedPathsWithContractPaths(OpenAPI openAPI) {
        List<String> suppliedPaths = Stream.of(filterArguments.getPaths().split(",")).collect(Collectors.toList());
        if (suppliedPaths.isEmpty() || filterArguments.getPaths().equalsIgnoreCase(ALL)) {
            suppliedPaths.remove(ALL);
            suppliedPaths.addAll(openAPI.getPaths().keySet());
        }
        suppliedPaths = CatsUtil.filterAndPrintNotMatching(suppliedPaths, path -> openAPI.getPaths().containsKey(path), LOGGER, "Supplied path is not matching the contract {}", Object::toString);

        return suppliedPaths;
    }

    public OpenAPI createOpenAPI() {
        try {
            long t0 = System.currentTimeMillis();
            OpenAPIParser openAPIV3Parser = new OpenAPIParser();
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            options.setFlatten(true);
            OpenAPI openAPI = openAPIV3Parser.readContents(new String(Files.readAllBytes(Paths.get(contract)), Charsets.UTF_8), null, options).getOpenAPI();

            String finishMessage = ansi().fgGreen().a("Finished parsing the contract in {} ms").reset().toString();
            LOGGER.complete(finishMessage, (System.currentTimeMillis() - t0));
            return openAPI;
        } catch (Exception e) {
            LOGGER.fatal("Error parsing OPEN API contract {}", contract);
            throw new StopExecutionException();
        }
    }

    /**
     * Encapsulates logic regarding commands that need to deal with the contract. Like printing the available paths for example.
     *
     * @param openAPI the OpenAPI object parsed from the contract
     * @param args    program arguments
     */
    private void processContractDependentCommands(OpenAPI openAPI, String[] args) {
        if (this.isListContractPaths(args)) {
            LOGGER.star("Available paths:");
            openAPI.getPaths().keySet().stream().sorted().map(item -> "\t " + item).forEach(LOGGER::info);
            throw new StopExecutionException("list available paths");
        }
    }

    private boolean isListContractPaths(String[] args) {
        return args.length == 3 && args[0].equalsIgnoreCase(LIST) && args[1].equalsIgnoreCase(PATHS_STRING);
    }

    private void processArgs(String[] args) {
        if (args.length == 0) {
            this.processNoArgument();
        }
        if (args.length == 1) {
            this.processSingleArgument(args);
        }

        if (args.length == 2) {
            this.processTwoArguments(args);
        }

        this.processRemainingArguments(args);
        this.processLogLevelArgument();
        this.setReportingLevel();
    }

    private void processRemainingArguments(String[] args) {
        if (this.isMinimumArgumentsNotSupplied(args)) {
            LOGGER.error("Missing or invalid required arguments 'contract' or 'server'. Usage: ./cats.jar --server=URL --contract=location. You can run './cats.jar' with no arguments for more options.");
            throw new StopExecutionException("minimum arguments not supplied");
        }
    }

    private boolean isMinimumArgumentsNotSupplied(String[] args) {
        return EMPTY.equalsIgnoreCase(contract) && (args.length != 3 || EMPTY.equalsIgnoreCase(server));
    }

    private void setReportingLevel() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.endava.cats")).setLevel(Level.toLevel(reportingArguments.getReportingLevel()));
    }

    private void processLogLevelArgument() {
        if (reportingArguments.hasLogData()) {
            String[] log = reportingArguments.getLogData().split(":");
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(log[0])).setLevel(Level.toLevel(log[1]));
            LOGGER.info("Setting log level to {} for package {}", log[1], log[0]);
        }
    }

    private void processTwoArguments(String[] args) {
        if (this.isListFuzzers(args)) {
            String message = ansi().bold().fg(Ansi.Color.GREEN).a("CATS has {} registered fuzzers:").reset().toString();
            LOGGER.info(message, fuzzers.size());
            filterAndDisplay(FieldFuzzer.class);
            filterAndDisplay(HeaderFuzzer.class);
            filterAndDisplay(HttpFuzzer.class);
            filterAndDisplay(ContractInfoFuzzer.class);
            filterAndDisplay(SpecialFuzzer.class);

            throw new StopExecutionException("list fuzzers");
        }

        if (this.isListFieldsFuzzingStrategies(args)) {
            LOGGER.info("Registered fieldsFuzzerStrategies: {}", Arrays.asList(FuzzingData.SetFuzzingStrategy.values()));
            throw new StopExecutionException("list fieldsFuzzerStrategies");
        }
    }

    private void filterAndDisplay(Class<? extends Annotation> annotation) {
        List<Fuzzer> fieldFuzzers = fuzzers.stream().filter(fuzzer -> AnnotationUtils.findAnnotation(fuzzer.getClass(), annotation) != null).collect(Collectors.toList());
        String message = ansi().bold().fg(Ansi.Color.CYAN).a("{} {} Fuzzers:").reset().toString();
        String typeOfFuzzers = annotation.getSimpleName().replace("Fuzzer", "");
        LOGGER.info(" ");
        LOGGER.info(message, fieldFuzzers.size(), typeOfFuzzers);
        fieldFuzzers.stream().map(fuzzer -> "\t ◼ " + ansi().bold().fg(Ansi.Color.GREEN).a(fuzzer.toString()).reset().a(" - " + fuzzer.description()).reset()).forEach(LOGGER::info);
    }

    private boolean isListFieldsFuzzingStrategies(String[] args) {
        return args[0].equalsIgnoreCase(LIST) && args[1].equalsIgnoreCase(FIELDS_FUZZER_STRATEGIES);
    }

    private boolean isListFuzzers(String[] args) {
        return args[0].equalsIgnoreCase(LIST) && args[1].equalsIgnoreCase(FUZZERS_STRING);
    }

    private void processNoArgument() {
        this.printCommands();
        this.printUsage();
        throw new StopExecutionException("list all commands");
    }

    private void processSingleArgument(String[] args) {
        if (args[0].equalsIgnoreCase(HELP)) {
            this.printUsage();
            throw new StopExecutionException(HELP);
        }

        if (args[0].equalsIgnoreCase(VERSION)) {
            throw new StopExecutionException(VERSION);
        }
    }

    protected void fuzzPath(Map.Entry<String, PathItem> pathItemEntry, OpenAPI openAPI) {
        List<String> configuredFuzzers = filterArguments.getFuzzersForPath(pathItemEntry.getKey());
        Map<String, Schema> schemas = getSchemas(openAPI);

        /* WE NEED TO ITERATE THROUGH EACH HTTP OPERATION CORRESPONDING TO THE CURRENT PATH ENTRY*/
        LOGGER.info(" ");
        LOGGER.start("Start fuzzing path {}", pathItemEntry.getKey());
        List<FuzzingData> fuzzingDataList = fuzzingDataFactory.fromPathItem(pathItemEntry.getKey(), pathItemEntry.getValue(), schemas, openAPI);

        if (fuzzingDataList.isEmpty()) {
            LOGGER.warning("Skipping path {}. HTTP method not supported yet!", pathItemEntry.getKey());
            return;
        }

        LOGGER.info("{} configured fuzzers out of {} total fuzzers: {}", configuredFuzzers.size(), fuzzers.size(), configuredFuzzers);

        /*We only run the fuzzers supplied and exclude those that do not apply for certain HTTP methods*/
        for (Fuzzer fuzzer : fuzzers) {
            if (configuredFuzzers.contains(fuzzer.toString())) {
                CatsUtil.filterAndPrintNotMatching(fuzzingDataList, data -> !fuzzer.skipFor().contains(data.getMethod()),
                        LOGGER, "HTTP method {} is not supported by {}", t -> t.getMethod().toString(), fuzzer.toString())
                        .forEach(data -> {
                            LOGGER.info("Fuzzer {} and payload: {}", ansi().fgGreen().a(fuzzer.toString()).reset(), data.getPayload());
                            fuzzer.fuzz(data);
                        });
            } else {
                LOGGER.debug("Skipping fuzzer {} for path {} as configured!", fuzzer, pathItemEntry.getKey());
            }
        }
    }


    private void printUsage() {
        LOGGER.info("The following arguments are supported: ");
        this.renderHelpToConsole("contract", "LOCATION_OF_THE_CONTRACT");
        this.renderHelpToConsole("server", "BASE_URL_OF_THE_SERVICE");
        this.renderHelpToConsole(FUZZERS_STRING, "COMMA_SEPARATED_LIST_OF_FUZZERS the list of fuzzers you want to run. You can use 'all' to include all fuzzers. To list all available fuzzers run: './cats.jar list fuzzers'");
        this.renderHelpToConsole("log", "PACKAGE:LEVEL set custom log level of a given package");
        this.renderHelpToConsole(PATHS_STRING, "PATH_LIST a comma separated list of paths to test. If no path is supplied, all paths will be considered");
        this.renderHelpToConsole("fieldsFuzzingStrategy", "STRATEGY set the strategy for tge fields fuzzers. Supported strategies ONEBYONE, SIZE, POWERSET");
        this.renderHelpToConsole("maxFieldsToRemove", "NUMBER set the maximum number of fields that will be removed from a request when using the SIZE fieldsFuzzingStrategy");
        this.renderHelpToConsole("refData", "FILE specifies the file with fields that must have a fixed value in order for requests to succeed ");
        this.renderHelpToConsole("headers", "FILE specifies custom headers that will be passed along with request. This can be used to pass oauth or JWT tokens for authentication purposed for example");
        this.renderHelpToConsole("reportingLevel", "LEVEL this can be either INFO, WARN or ERROR. It can be used to suppress INFO logging and focus only on the reporting WARNS and/or ERRORS");
        this.renderHelpToConsole("edgeSpacesStrategy", "STRATEGY this can be either validateAndTrim or trimAndValidate. It can be used to specify what CATS should expect when sending trailing and leading spaces valid values within fields");
        this.renderHelpToConsole("urlParams", "A comma separated list of 'name:value' pairs of parameters to be replaced inside the URLs");
        this.renderHelpToConsole("customFuzzerFile", "A file used by the `CustomFuzzer` that will be used to create user-supplied payloads");
        this.renderHelpToConsole("skipXXXForPath", "/path1,/path2 can configure fuzzers to be excluded for the specified paths");
        this.renderHelpToConsole("excludeFuzzers", "COMMA_SEPARATED_LIST_OF_FUZZERS the list of fuzzers you want to exclude");
        this.renderHelpToConsole("securityFuzzerFile", "A file used by the `SecurityFuzzer` that will be used to inject special strings in order to exploit possible vulnerabilities");
        this.renderHelpToConsole("printExecutionStatistics", "If supplied (no value needed), prints a summary of execution times for each endpoint and HTTP method");
        this.renderHelpToConsole("useExamples", "true/false (default true), instruct CATS on whether to use examples from the OpenAPI contract or not");
        this.renderHelpToConsole("checkFields", "If supplied (no value needed), it will only run the Field Fuzzers");
        this.renderHelpToConsole("checkHeaders", "If supplied (no value needed), it will only run the Header Fuzzers");
        this.renderHelpToConsole("checkHttp", "If supplied (no value needed), it will only run the HTTP Fuzzers");
        this.renderHelpToConsole("checkContract", "If supplied (no value needed), it will only run the ContractInfo Fuzzers");
        this.renderHelpToConsole("sslKeystore", "Location of the keystore holding certificates used when authenticating calls using one-way or two-way SSL");
        this.renderHelpToConsole("sslKeystorePwd", "The password of the sslKeystore");
        this.renderHelpToConsole("sslKeyPwd", "The password of the private key from the sslKeystore");
        this.renderHelpToConsole("basicauth", "Supplies a `username:password` pair, in case the service uses basic auth");

        LOGGER.note("Example: ");
        LOGGER.note(EXAMPLE);
    }

    private void printCommands() {
        LOGGER.info("Available commands:");
        LOGGER.info("\t./cats.jar help");
        LOGGER.info("\t./cats.jar version");
        LOGGER.info("\t./cats.jar list fuzzers");
        LOGGER.info("\t./cats.jar list fieldsFuzzerStrategies");
        LOGGER.info("\t./cats.jar list paths --contract=CONTRACT");
    }

    private void printArgs() {
        LOGGER.info("server: {}", server);
        LOGGER.info("contract: {}", contract);
        LOGGER.info("{} registered fuzzers: {}", fuzzers.size(), fuzzers);
        LOGGER.info("supplied fuzzers: {}", filterArguments.getSuppliedFuzzers());
        LOGGER.info("fields fuzzing strategy: {}", processingArguments.getFieldsFuzzingStrategy());
        LOGGER.info("max fields to remove: {}", processingArguments.getMaxFieldsToRemove());
        LOGGER.info("paths: {}", filterArguments.getPaths());
        LOGGER.info("refData: {}", filesArguments.getRefDataFile());
        LOGGER.info("headers: {}", filesArguments.getHeadersFile());
        LOGGER.info("reportingLevel: {}", reportingArguments.getReportingLevel());
        LOGGER.info("edgeSpacesStrategy: {}", processingArguments.getEdgeSpacesStrategy());
        LOGGER.info("urlParams: {}", filesArguments.getParams());
        LOGGER.info("customFuzzerFile: {}", filesArguments.getCustomFuzzerFile());
        LOGGER.info("securityFuzzerFile: {}", filesArguments.getSecurityFuzzerFile());
        LOGGER.info("printExecutionStatistic: {}", reportingArguments.printExecutionStatistics());
        LOGGER.info("excludeFuzzers: {}", filterArguments.getExcludedFuzzers());
        LOGGER.info("useExamples: {}", processingArguments.getUseExamples());
        LOGGER.info("log: {}", reportingArguments.getLogData());
        LOGGER.info("checkFields: {}", checkArgs.checkFields());
        LOGGER.info("checkHeaders: {}", checkArgs.checkHeaders());
        LOGGER.info("checkHttp: {}", checkArgs.checkHttp());
        LOGGER.info("checkContract: {}", checkArgs.checkContract());
        LOGGER.info("sslKeystore: {}", authArgs.getSslKeystore());
        LOGGER.info("sslKeystorePwd: {}", authArgs.getSslKeystorePwd());
        LOGGER.info("sslKeyPwd: {}", authArgs.getSslKeyPwd());
        LOGGER.info("basicauth: {}", authArgs.getBasicAuth());

    }

    private void renderHelpToConsole(String command, String text) {
        LOGGER.info(COMMAND_TEMPLATE, command, text);
    }

    @Override
    public int getExitCode() {
        return executionStatisticsListener.getErrors();
    }
}