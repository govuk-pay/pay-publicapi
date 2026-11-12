package uk.gov.pay.api.service;

import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit.PactProviderRule;
import au.com.dius.pact.consumer.junit.PactVerification;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import jakarta.ws.rs.client.Client;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import uk.gov.pay.api.app.RestClientFactory;
import uk.gov.pay.api.app.config.PublicApiConfig;
import uk.gov.pay.api.app.config.RestClientConfig;
import uk.gov.pay.api.auth.Account;
import uk.gov.pay.api.exception.CancelAgreementException;
import uk.gov.pay.api.model.TokenPaymentType;
import uk.gov.service.payments.commons.model.ErrorIdentifier;
import uk.gov.service.payments.commons.testing.pact.consumers.Pacts;
import static org.hamcrest.Matchers.hasProperty;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class CancelAgreementConnectorServicePactTest {

    private final Account ACCOUNT = new Account("123456", TokenPaymentType.CARD, "a-token-link");
    
    @Rule
    public PactProviderRule connectorRule = new PactProviderRule("connector", this);

    private ConnectorService connectorService;
    @Mock
    private PublicApiConfig mockConfiguration;

    @Before
    public void setUp() {
        when(mockConfiguration.getConnectorUrl()).thenReturn(connectorRule.getUrl());
        ConnectorUriGenerator connectorUriGenerator = new ConnectorUriGenerator(mockConfiguration);
        Client client = RestClientFactory.buildClient(new RestClientConfig(false));
        connectorService = new ConnectorService(client, connectorUriGenerator);
    }
    
    @Pact(consumer = "publicapi")
    public RequestResponsePact cancelAnAgreementWithActiveStatus(PactDslWithProvider builder) {
        return builder
                .given("a gateway account and an active agreement exists", 
                        Map.of("agreement_external_id", "agreement1234567",
                                "gateway_account_id", "123456"))
                .uponReceiving("cancel an agreement with active status")
                .path("/v1/api/accounts/123456/agreements/agreement1234567/cancel")
                .method("POST")
                .willRespondWith()
                .status(204)
                .toPact();
    }

    @Test
    @PactVerification(fragment = "cancelAnAgreementWithActiveStatus")
    public void cancelAnAgreementWithActiveStatus() {
        connectorService.cancelAgreement(ACCOUNT, "agreement1234567");
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact cancelAnAgreementWithCancelledStatus(PactDslWithProvider builder) {
        return builder
                .given("a gateway account and an agreement with cancelled status exists",
                        Map.of("agreement_external_id", "agreement9876543",
                                "gateway_account_id", "123456"))
                .uponReceiving("cancel an agreement with cancelled status")
                .path("/v1/api/accounts/123456/agreements/agreement9876543/cancel")
                .method("POST")
                .willRespondWith()
                .status(400)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringValue("error_identifier", "AGREEMENT_NOT_ACTIVE")
                        .array("message")
                            .stringValue("Payment instrument not active.")
                        .closeArray())
                .toPact();
    }
    
    @Test
    @PactVerification(fragment = "cancelAnAgreementWithCancelledStatus")
    public void cancelAnAgreementWithCancelledStatus() {
        CancelAgreementException cancelAgreementException = assertThrows(CancelAgreementException.class,
                () -> connectorService.cancelAgreement(ACCOUNT, "agreement9876543"));
        assertThat(cancelAgreementException, hasProperty("errorStatus", is(400)));
        assertThat(cancelAgreementException, hasProperty("errorIdentifier", is(ErrorIdentifier.AGREEMENT_NOT_ACTIVE)));
        assertThat(cancelAgreementException.getConnectorErrorMessage(), is("Payment instrument not active."));
    }
    
}
