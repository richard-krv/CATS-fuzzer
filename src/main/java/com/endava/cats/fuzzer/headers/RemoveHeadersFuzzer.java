package com.endava.cats.fuzzer.headers;

import com.endava.cats.Fuzzer;
import com.endava.cats.annotations.HeaderFuzzer;
import com.endava.cats.http.ResponseCodeFamily;
import com.endava.cats.io.ServiceCaller;
import com.endava.cats.io.ServiceData;
import com.endava.cats.model.CatsHeader;
import com.endava.cats.model.CatsResponse;
import com.endava.cats.model.FuzzingData;
import com.endava.cats.report.TestCaseListener;
import com.endava.cats.util.ConsoleUtils;
import io.github.ludovicianul.prettylogger.PrettyLogger;
import io.github.ludovicianul.prettylogger.PrettyLoggerFactory;

import javax.inject.Singleton;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
@HeaderFuzzer
public class RemoveHeadersFuzzer implements Fuzzer {
    private final PrettyLogger logger = PrettyLoggerFactory.getLogger(RemoveHeadersFuzzer.class);
    private final ServiceCaller serviceCaller;
    private final TestCaseListener testCaseListener;

    public RemoveHeadersFuzzer(ServiceCaller sc, TestCaseListener lr) {
        this.serviceCaller = sc;
        this.testCaseListener = lr;
    }

    public void fuzz(FuzzingData data) {
        if (data.getHeaders().isEmpty()) {
            logger.skip("No headers to fuzz");
            return;
        }

        Set<Set<CatsHeader>> headersCombination = FuzzingData.SetFuzzingStrategy.powerSet(data.getHeaders());
        Set<CatsHeader> mandatoryHeaders = data.getHeaders().stream().filter(CatsHeader::isRequired).collect(Collectors.toSet());

        for (Set<CatsHeader> headersSubset : headersCombination) {
            testCaseListener.createAndExecuteTest(logger, this, () -> process(data, headersSubset, mandatoryHeaders));
        }
    }

    private void process(FuzzingData data, Set<CatsHeader> headersSubset, Set<CatsHeader> requiredHeaders) {
        testCaseListener.addScenario(logger, "Send only the following headers: {} plus any authentication headers.", headersSubset);
        boolean anyMandatoryHeaderRemoved = this.isAnyMandatoryHeaderRemoved(headersSubset, requiredHeaders);

        testCaseListener.addExpectedResult(logger, "Should return [{}] response code as mandatory headers [{}] removed", ResponseCodeFamily.getExpectedWordingBasedOnRequiredFields(anyMandatoryHeaderRemoved));

        CatsResponse response = serviceCaller.call(ServiceData.builder().relativePath(data.getPath()).headers(headersSubset)
                .payload(data.getPayload()).addUserHeaders(false).queryParams(data.getQueryParams()).httpMethod(data.getMethod())
                .contentType(data.getFirstRequestContentType()).build());
        testCaseListener.reportResult(logger, data, response, ResponseCodeFamily.getResultCodeBasedOnRequiredFieldsRemoved(anyMandatoryHeaderRemoved));
    }

    private boolean isAnyMandatoryHeaderRemoved(Set<CatsHeader> headersSubset, Set<CatsHeader> requiredHeaders) {
        Set<CatsHeader> intersection = new HashSet<>(requiredHeaders);
        intersection.retainAll(headersSubset);
        return intersection.size() != requiredHeaders.size();
    }

    @Override
    public String toString() {
        return ConsoleUtils.sanitizeFuzzerName(this.getClass().getSimpleName());
    }

    @Override
    public String description() {
        return "iterate through each header and remove different combinations of them";
    }
}
