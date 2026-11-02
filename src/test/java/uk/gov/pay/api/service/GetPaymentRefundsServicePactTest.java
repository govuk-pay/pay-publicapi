package uk.gov.pay.api.service;

import au.com.dius.pact.consumer.junit.PactVerification;
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
import uk.gov.pay.api.ledger.service.LedgerUriGenerator;
import uk.gov.pay.api.model.RefundResponse;
import uk.gov.pay.api.model.RefundsResponse;
import uk.gov.pay.api.model.TokenPaymentType;
import uk.gov.service.payments.commons.testing.pact.consumers.Pacts;
import uk.gov.service.payments.commons.testing.pact.consumers.PayPactProviderRule;

import jakarta.ws.rs.client.Client;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class GetPaymentRefundsServicePactTest {

    private static final String ACCOUNT_ID = "123456";
    @Rule
    public PayPactProviderRule ledgerRule = new PayPactProviderRule("ledger", this);
    @Rule
    public PayPactProviderRule connectorRule = new PayPactProviderRule("connector", this);
    @Mock
    private PublicApiConfig mockConfiguration;
    private GetPaymentRefundsService getPaymentRefundsService;

    @Before
    public void setUp() {
        when(mockConfiguration.getLedgerUrl()).thenReturn(ledgerRule.getUrl());
        when(mockConfiguration.getBaseUrl()).thenReturn("http://publicapi.test.localhost/");

        PublicApiUriGenerator publicApiUriGenerator = new PublicApiUriGenerator(mockConfiguration);
        LedgerUriGenerator ledgerUriGenerator = new LedgerUriGenerator(mockConfiguration);

        Client client = RestClientFactory.buildClient(new RestClientConfig(false));
        LedgerService ledgerService = new LedgerService(client, ledgerUriGenerator);

        getPaymentRefundsService = new GetPaymentRefundsService(ledgerService, publicApiUriGenerator);
    }

    @Test
    @PactVerification("ledger")
    @Pacts(pacts = {"publicapi-ledger-get-payment-refunds"})
    public void shouldReturnRefundsForPaymentCorrectlyFromLedger() {
        Account account = new Account(ACCOUNT_ID, TokenPaymentType.CARD, "a-token-link");
        RefundsResponse response = getPaymentRefundsService.getLedgerTransactionTransactions(account, "ch_123abc456xyz");
        assertThat(response.getPaymentId(), is("ch_123abc456xyz"));
        assertThat(response.getLinks().getSelf().getHref(), is("http://publicapi.test.localhost/v1/payments/ch_123abc456xyz/refunds"));
        assertThat(response.getLinks().getPayment().getHref(), is("http://publicapi.test.localhost/v1/payments/ch_123abc456xyz"));

        List<RefundResponse> refunds = response.getEmbedded().getRefunds();
        assertThat(refunds.size(), is(2));
        
        assertThat(refunds.get(0).getRefundId(), is("refund-transaction-id1"));
        assertThat(refunds.get(0).getStatus(), is("submitted"));
        assertThat(refunds.get(0).getAmount(), is(100L));
        assertThat(refunds.get(0).getFee(), is(2L));
        assertThat(refunds.get(0).getNetAmount(), is(-102L));
        assertThat(refunds.get(0).getLinks().getPayment().getHref(), is("http://publicapi.test.localhost/v1/payments/ch_123abc456xyz"));
        assertThat(refunds.get(0).getLinks().getSelf().getHref(), is("http://publicapi.test.localhost/v1/payments/ch_123abc456xyz/refunds/refund-transaction-id1"));

        assertThat(refunds.get(1).getRefundId(), is("refund-transaction-id2"));
        assertThat(refunds.get(1).getStatus(), is("error"));
        assertThat(refunds.get(1).getAmount(), is(200L));
        assertThat(refunds.get(1).getFee(), is(3L));
        assertThat(refunds.get(1).getNetAmount(), is(-203L));
        assertThat(refunds.get(1).getLinks().getPayment().getHref(), is("http://publicapi.test.localhost/v1/payments/ch_123abc456xyz"));
        assertThat(refunds.get(1).getLinks().getSelf().getHref(), is("http://publicapi.test.localhost/v1/payments/ch_123abc456xyz/refunds/refund-transaction-id2"));
    }
}
