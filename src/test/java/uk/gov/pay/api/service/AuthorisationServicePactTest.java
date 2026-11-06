package uk.gov.pay.api.service;

import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit.PactProviderRule;
import au.com.dius.pact.consumer.junit.PactVerification;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import uk.gov.pay.api.app.RestClientFactory;
import uk.gov.pay.api.app.config.PublicApiConfig;
import uk.gov.pay.api.app.config.RestClientConfig;
import uk.gov.pay.api.exception.AuthorisationRequestException;
import uk.gov.pay.api.exception.mapper.AuthorisationRequestExceptionMapper;
import uk.gov.pay.api.model.AuthorisationRequest;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AuthorisationServicePactTest {
    private AuthorisationService authorisationService;
    private AuthorisationRequest request;
    private AuthorisationRequestExceptionMapper mapper;

    @Rule
    public PactProviderRule connectorRule = new PactProviderRule("connector", this);

    @Mock
    private PublicApiConfig mockConfiguration;

    @Before
    public void setup() {
        when(mockConfiguration.getConnectorUrl()).thenReturn(connectorRule.getUrl());
        ConnectorUriGenerator uriGenerator = new ConnectorUriGenerator(mockConfiguration);
        Client client = RestClientFactory.buildClient(new RestClientConfig(false));
        authorisationService = new AuthorisationService(client, uriGenerator);
        mapper = new AuthorisationRequestExceptionMapper();
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact authorisePayment(PactDslWithProvider builder) {
        return builder
                .given("a charge with authorisation mode moto_api and one_time_token exists",
                        Map.of("gateway_account_id", "123456",
                                "charge_id", "ch_e36c168c41a04",
                                "one_time_token", "onetime-12345-token",
                                "one_time_token_used", "false"))
                .uponReceiving("an authorise charge request with moto api and valid one time token")
                .method("POST")
                .path("/v1/api/charges/authorise")
                .body(new PactDslJsonBody()
                                .stringValue("one_time_token", "onetime-12345-token")
                                .stringValue("card_number", "4242424242424242")
                                .stringValue("cvc", "123")
                                .stringValue("expiry_date", "09/29")
                                .stringValue("cardholder_name", "Joe Boggs"))
                .headers(Map.of("Content-Type", "application/json"))
                .willRespondWith()
                .status(204)
                .toPact();
    }

    @Test
    @PactVerification(fragment = "authorisePayment")
    public void authorisePayment() {
        request = new AuthorisationRequest("onetime-12345-token", "4242424242424242", "123",
                "09/29", "Joe Boggs");
        Response response = authorisationService.authoriseRequest(request);
        assertThat(response.getStatus(), is(204));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact invalidToken(PactDslWithProvider builder) {
        return builder
                .given("a charge with authorisation mode moto_api and one_time_token exists",
                        Map.of("gateway_account_id", "123456",
                                "charge_id", "ch_e36c168c41a04",
                                "one_time_token", "onetime-12345-token",
                                "one_time_token_used", "false"))
                .uponReceiving("an authorise charge with moto api and invalid one time token")
                .method("POST")
                .path("/v1/api/charges/authorise")
                .body(new PactDslJsonBody()
                        .stringValue("one_time_token", "invalid-token")
                        .stringValue("card_number", "4242424242424242")
                        .stringValue("cvc", "123")
                        .stringValue("expiry_date", "09/29")
                        .stringValue("cardholder_name", "Joe Boggs"))
                .headers(Map.of("Content-Type", "application/json"))
                .willRespondWith()
                .status(400)
                .body(new PactDslJsonBody()
                        .stringValue("error_identifier", "ONE_TIME_TOKEN_INVALID")
                        .array("message")
                            .stringValue("The one_time_token is not valid")
                        .closeArray())
                .toPact();
    }

    @Test
    @PactVerification(fragment = "invalidToken")
    public void invalidToken() {
        request = new AuthorisationRequest("invalid-token", "4242424242424242", "123",
                "09/29", "Joe Boggs");
        AuthorisationRequestException authorisationRequestException = assertThrows(AuthorisationRequestException.class,
                () -> authorisationService.authoriseRequest(request));

        assertThat(authorisationRequestException, hasProperty("errorStatus", is(400)));
    }
    
    @Pact(consumer = "publicapi")
    public RequestResponsePact alreadyUsedToken(PactDslWithProvider builder) {
        return builder
                .given("a charge with authorisation mode moto_api and one_time_token exists",
                        Map.of("gateway_account_id", "123456",
                                "charge_id", "ch_e36c168c41a04",
                                "one_time_token", "onetime-12345-token",
                                "one_time_token_used", "true"))
                .uponReceiving("an authorise charge with moto api and invalid one time token")
                .method("POST")
                .path("/v1/api/charges/authorise")
                .body(new PactDslJsonBody()
                        .stringValue("one_time_token", "onetime-12345-token")
                        .stringValue("card_number", "4242424242424242")
                        .stringValue("cvc", "123")
                        .stringValue("expiry_date", "09/29")
                        .stringValue("cardholder_name", "Joe Boggs"))
                .headers(Map.of("Content-Type", "application/json"))
                .willRespondWith()
                .status(400)
                .body(new PactDslJsonBody()
                        .stringValue("error_identifier", "ONE_TIME_TOKEN_INVALID")
                        .array("message")
                            .stringValue("The one_time_token is not valid")
                        .closeArray())
                .toPact();
    }

    @Test
    @PactVerification(fragment = "alreadyUsedToken")
    public void alreadyUsedToken() {
        request = new AuthorisationRequest("onetime-12345-token", "4242424242424242", "123",
                "09/29", "Joe Boggs");
        AuthorisationRequestException authorisationRequestException = assertThrows(AuthorisationRequestException.class,
                () -> authorisationService.authoriseRequest(request));

        assertThat(authorisationRequestException, hasProperty("errorStatus", is(400)));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact invalidCardNumber(PactDslWithProvider builder) {
        return builder
                .given("a charge with authorisation mode moto_api and one_time_token exists",
                        Map.of("gateway_account_id", "123456",
                                "charge_id", "ch_e36c168c41a04",
                                "one_time_token", "onetime-12345-token",
                                "one_time_token_used", "false"))
                .uponReceiving("an authorise charge with moto api and invalid one time token")
                .method("POST")
                .path("/v1/api/charges/authorise")
                .body(new PactDslJsonBody()
                        .stringValue("one_time_token", "onetime-12345-token")
                        .stringValue("card_number", "0000000000000000")
                        .stringValue("cvc", "123")
                        .stringValue("expiry_date", "09/29")
                        .stringValue("cardholder_name", "Joe Boggs"))
                .headers(Map.of("Content-Type", "application/json"))
                .willRespondWith()
                .status(402)
                .body(new PactDslJsonBody()
                        .stringValue("error_identifier", "CARD_NUMBER_REJECTED")
                        .array("message")
                        .stringValue("The card_number is not a valid card number")
                        .closeArray())
                .toPact();
    }


    @Test
    @PactVerification(fragment = "invalidCardNumber")
    public void invalidCardNumber() {
        request = new AuthorisationRequest("onetime-12345-token", "0000000000000000", "123",
                "09/29", "Joe Boggs");
        AuthorisationRequestException authorisationRequestException = assertThrows(AuthorisationRequestException.class,
                () -> authorisationService.authoriseRequest(request));

        assertThat(authorisationRequestException, hasProperty("errorStatus", is(402)));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact invalidExpiryDate(PactDslWithProvider builder) {
        return builder
                .given("a charge with authorisation mode moto_api and one_time_token exists",
                        Map.of("gateway_account_id", "123456",
                                "charge_id", "ch_e36c168c41a04",
                                "one_time_token", "onetime-12345-token",
                                "one_time_token_used", "false"))
                .uponReceiving("an authorise charge request with moto api and invalid attribute")
                .method("POST")
                .path("/v1/api/charges/authorise")
                .body(new PactDslJsonBody()
                        .stringValue("one_time_token", "onetime-12345-token")
                        .stringValue("card_number", "0000000000000000")
                        .stringValue("cvc", "123")
                        .stringValue("expiry_date", "09/21")
                        .stringValue("cardholder_name", "Joe Boggs"))
                .headers(Map.of("Content-Type", "application/json"))
                .willRespondWith()
                .status(422)
                .body(new PactDslJsonBody()
                        .stringValue("error_identifier", "INVALID_ATTRIBUTE_VALUE")
                        .array("message")
                        .stringValue("Invalid attribute value: expiry_date. Must be a valid date in the future")
                        .closeArray())
                .toPact();
    }

    @Test
    @PactVerification(fragment = "invalidExpiryDate")
    public void invalidExpiryDate() {
        request = new AuthorisationRequest("onetime-12345-token", "0000000000000000", "123",
                "09/21", "Joe Boggs");
        AuthorisationRequestException authorisationRequestException = assertThrows(AuthorisationRequestException.class,
                () -> authorisationService.authoriseRequest(request));

        assertThat(authorisationRequestException, hasProperty("errorStatus", is(422)));
    }
}
