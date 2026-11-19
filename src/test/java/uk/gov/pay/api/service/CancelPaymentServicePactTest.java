package uk.gov.pay.api.service;

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
import uk.gov.pay.api.auth.Account;
import uk.gov.pay.api.model.TokenPaymentType;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class CancelPaymentServicePactTest {

    private final Account ACCOUNT = new Account("123456", TokenPaymentType.CARD, "a-token-link");
    
    private CancelPaymentService cancelPaymentService;

    @Rule
    public PactProviderRule connectorRule = new PactProviderRule("connector", this);

    @Mock
    private PublicApiConfig mockConfiguration;
    
    @Before
    public void setUp() {
        when(mockConfiguration.getConnectorUrl()).thenReturn(connectorRule.getUrl());
        ConnectorUriGenerator connectorUriGenerator = new ConnectorUriGenerator(mockConfiguration);
        Client client = RestClientFactory.buildClient(new RestClientConfig(false));
        cancelPaymentService = new CancelPaymentService(client, connectorUriGenerator);
    }
    
    @Pact(consumer = "publicapi")
    public RequestResponsePact cancelAPaymentWithCreatedState(PactDslWithProvider builder) {
        return builder
                .given("a charge with card details exists",
                        Map.of("charge_id", "charge8133029783750964639",
                                "gateway_account_id", "123456"))
                .uponReceiving("cancel an agreement with active status")
                .path("/v1/api/accounts/123456/charges/charge8133029783750964639/cancel")
                .method("POST")
                .willRespondWith()
                .status(204)
                .toPact();
    }

    @Test
    @PactVerification(fragment = "cancelAPaymentWithCreatedState")
    public void cancelAPaymentWithCreatedState() {
        Response cancelPaymentResponse = cancelPaymentService.cancel(ACCOUNT, "charge8133029783750964639");
        assertThat(cancelPaymentResponse.getStatus(), is(204));
    }
}
