package com.banamex.transferlimits.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.banamex.transferlimits.api.error.ProblemType;
import com.banamex.transferlimits.api.error.RejectionMessageFactory;
import com.banamex.transferlimits.application.GetAccountLimitsUseCase;
import com.banamex.transferlimits.application.UpdateChannelLimitUseCase;
import com.banamex.transferlimits.application.ValidateTransferUseCase;
import com.banamex.transferlimits.config.observability.TraceIdProvider;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Verifies that the contract-first {@code openapi.yaml} and the Spring MVC mappings describe
 * exactly the same operations, and that the problem catalogue matches the examples of the contract.
 */
@WebMvcTest(controllers = {AccountLimitsController.class, TransferValidationController.class})
@Import(RejectionMessageFactory.class)
class ContractConformanceTest {

  private static final String API_PREFIX = "/api/";

  private static OpenAPI contract;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  @MockitoBean private GetAccountLimitsUseCase getAccountLimitsUseCase;

  @MockitoBean private UpdateChannelLimitUseCase updateChannelLimitUseCase;

  @MockitoBean private ValidateTransferUseCase validateTransferUseCase;

  @MockitoBean private TraceIdProvider traceIdProvider;

  @BeforeAll
  static void parseContract() throws Exception {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    String location = new ClassPathResource("openapi/openapi.yaml").getURL().toString();
    SwaggerParseResult result = new OpenAPIV3Parser().readLocation(location, null, options);
    assertThat(result.getMessages()).as("openapi.yaml parse messages").isEmpty();
    contract = result.getOpenAPI();
    assertThat(contract).isNotNull();
  }

  @Test
  void everyContractOperationIsImplementedAndViceVersa() {
    Set<String> documented = new TreeSet<>();
    contract
        .getPaths()
        .forEach((path, item) ->
            item.readOperationsMap()
                .keySet()
                .forEach(method -> documented.add(method.name() + " " + path)));

    Set<String> implemented = new TreeSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      for (String path : info.getPathPatternsCondition().getPatternValues()) {
        if (path.startsWith(API_PREFIX)) {
          for (RequestMethod method : info.getMethodsCondition().getMethods()) {
            implemented.add(method.name() + " " + path);
          }
        }
      }
    }

    assertThat(documented)
        .containsExactlyInAnyOrder(
            "GET /api/v1/accounts/{accountId}/limits",
            "PUT /api/v1/accounts/{accountId}/limits/{channelId}",
            "POST /api/v1/accounts/{accountId}/transfer-validations");
    assertThat(implemented).isEqualTo(documented);
  }

  @Test
  void problemExamplesOfTheContractMatchTheCatalogue() {
    Map<String, Example> examples = contract.getComponents().getExamples();
    int problemExamples = 0;
    for (Example example : examples.values()) {
      Map<?, ?> value = Json.mapper().convertValue(example.getValue(), Map.class);
      if (value.containsKey("code")) {
        ProblemType type = ProblemType.valueOf(String.valueOf(value.get("code")));
        assertThat(String.valueOf(value.get("type"))).isEqualTo(type.type().toString());
        assertThat(String.valueOf(value.get("title"))).isEqualTo(type.title());
        assertThat(((Number) value.get("status")).intValue()).isEqualTo(type.status().value());
        problemExamples++;
      }
    }
    assertThat(problemExamples).isGreaterThanOrEqualTo(8);
  }

  @Test
  void everyCatalogueCodeIsDocumentedInTheContract() {
    String description =
        contract.getComponents().getSchemas().get("ProblemDetail").getDescription();
    for (ProblemType type : ProblemType.values()) {
      assertThat(description).contains("| " + type.code() + " | " + type.status().value() + " |");
    }
  }
}
