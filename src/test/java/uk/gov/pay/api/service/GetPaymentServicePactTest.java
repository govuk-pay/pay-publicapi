package uk.gov.pay.api.service;

import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit.PactProviderRule;
import au.com.dius.pact.consumer.junit.PactVerification;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import jakarta.ws.rs.client.Client;
import org.hamcrest.core.Is;
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
import uk.gov.pay.api.model.PaymentState;
import uk.gov.pay.api.model.TokenPaymentType;
import uk.gov.pay.api.model.Wallet;
import uk.gov.pay.api.model.links.PaymentWithAllLinks;
import uk.gov.pay.api.model.links.PostLink;
import uk.gov.service.payments.commons.model.AuthorisationMode;
import uk.gov.service.payments.commons.model.SupportedLanguage;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class GetPaymentServicePactTest {
    private GetPaymentService getPaymentService;
    private String ACCOUNT_ID = "123456";

    @Rule
    public PactProviderRule connectorRule = new PactProviderRule("connector",this);

    @Mock
    PublicApiConfig mockConfiguration;

    @Before
    public void setup() {
        when(mockConfiguration.getConnectorUrl()).thenReturn(connectorRule.getUrl());
        when(mockConfiguration.getBaseUrl()).thenReturn("http://publicapi.test.localhost/");

        PublicApiUriGenerator publicApiUriGenerator = new PublicApiUriGenerator(mockConfiguration);
        ConnectorUriGenerator connectorUriGenerator = new ConnectorUriGenerator(mockConfiguration);
        LedgerUriGenerator ledgerUriGenerator = new LedgerUriGenerator(mockConfiguration);
        Client client = RestClientFactory.buildClient(new RestClientConfig(false));
        getPaymentService = new GetPaymentService(publicApiUriGenerator,
                new ConnectorService(client, connectorUriGenerator),
                new LedgerService(client, ledgerUriGenerator));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact getCapturablePayment(PactDslWithProvider builder) {
        return builder
                .given("a charge exists",
                        Map.of("gateway_account_id", ACCOUNT_ID,
                                "charge_id", "charge1234ABCDE",
                                "gateway_transaction_id", "gateway-tx-123456"))
                .uponReceiving("get a delayed capture charge with metadata that is in a capturable status")
                .path("/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge1234ABCDE")
                .method("GET")
                .willRespondWith()
                .headers(Map.of("Content-Type", "application/json"))
                .status(200)
                .body(Objects.requireNonNull(new PactDslJsonBody()
                        .object("metadata", new PactDslJsonBody()
                                .stringValue("some_key", "key")
                                .integerType("ledger_code", 123))
                        .object("state", new PactDslJsonBody()
                                .stringMatcher("status", "submitted|capturable")
                                .booleanValue("finished", false))
                        .numberType("amount", 100)
                        .datetime("created_date", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                Date.from(Instant.parse("2020-02-13T16:26:04.204Z")))
                        .stringValue("charge_id", "charge1234ABCDE")
                        .stringValue("language", "en")
                        .stringType("description")
                        .stringType("reference")
                        .stringValue("gateway_transaction_id", "gateway-tx-123456")
                        .stringType("return_url", "https://somewhere.gov.uk/rainbow/1")
                        .stringValue("payment_provider", "sandbox")
                        .numberType("corporate_card_surcharge", 250)
                        .numberType("total_amount", 350)
                        .object("refund_summary", new PactDslJsonBody()
                                .stringValue("status", "unavailable")
                                .nullValue("user_external_id")
                                .numberType("amount_available", 350)
                                .numberValue("amount_submitted", 0))
                        .object("settlement_summary", new PactDslJsonBody()
                                .nullValue("capture_submit_time")
                                .nullValue("captured_date"))
                        .booleanValue("delayed_capture", true)
                        .booleanValue("moto", false)
                        .object("authorisation_summary", new PactDslJsonBody()
                                .object("three_d_secure", new PactDslJsonBody()
                                        .booleanType("required")
                                        .stringType("version")))
                        .array("links")
                        .object()
                        .stringValue("rel", "self")
                        .stringValue("method", "GET")
                        .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge1234ABCDE")
                        .closeObject()
                        .object()
                        .stringValue("rel", "refunds")
                        .stringValue("method", "GET")
                        .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge1234ABCDE/refunds")
                        .closeObject()
                        .object()
                        .stringValue("rel", "capture")
                        .stringValue("method", "POST")
                        .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge1234ABCDE/capture")
                        .closeObject()
                        .closeArray())
                )
                .toPact();
    }

    @Test
    @PactVerification(fragment = "getCapturablePayment")
    public void getCapturablePayment_shouldSerialiseConnectorChargeCorrectly() {
        String chargeId = "charge1234ABCDE";
        Account account = new Account(ACCOUNT_ID, TokenPaymentType.CARD, "a-token-link");
        PaymentWithAllLinks paymentResponse = getPaymentService.getPayment(account, chargeId);

        assertThat(paymentResponse.getProviderId(), is("gateway-tx-123456"));
        assertThat(paymentResponse.getCorporateCardSurcharge().get(), is(250L));
        assertThat(paymentResponse.getTotalAmount().get(), is(350L));
        assertThat(paymentResponse.getLinks().getCapture().getHref(),
                containsString("v1/payments/" + chargeId + "/capture"));
        assertThat(paymentResponse.getLinks().getCapture().getMethod(), is("POST"));
        assertThat(paymentResponse.getMetadata(), is(notNullValue()));
        assertThat(paymentResponse.getMetadata().getMetadata().isEmpty(), is(false));
        assertThat(paymentResponse.getLinks().getNextUrl(), is(nullValue()));
        assertThat(paymentResponse.getLinks().getNextUrlPost(), is(nullValue()));
        assertThat(paymentResponse.getAuthorisationSummary().getThreeDSecure().isRequired(), is(true));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact getAChargeRequestForACreatedPayment(PactDslWithProvider builder) {
        return builder
                .given("a charge with delayed capture true exists",
                        Map.of("gateway_account_id", ACCOUNT_ID,
                                "charge_id", "chargeABCDE1234"))
                .uponReceiving("get a charge request for a created payment")
                .path("/v1/api/accounts/" + ACCOUNT_ID + "/charges/chargeABCDE1234")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(Objects.requireNonNull(new PactDslJsonBody()
                        .integerType("amount", 100)
                        .object("state", new PactDslJsonBody()
                                .stringValue("status", "created")
                                .booleanValue("finished", false))
                        .stringType("description", "some description")
                        .stringType("reference", "some-reference")
                        .stringValue("language", "en")
                        .stringValue("charge_id", "chargeABCDE1234")
                        .datetime("created_date", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                Date.from(Instant.parse("2020-02-13T16:26:04.204Z")))
                        .stringType("return_url", "https://somewhere.gov.uk/rainbow/1")
                        .stringType("payment_provider", "sandbox")
                        .object("settlement_summary", new PactDslJsonBody()
                                .nullValue("capture_submit_time")
                                .nullValue("captured_date"))
                        .object("refund_summary", new PactDslJsonBody()
                                .stringValue("status", "pending")
                                .nullValue("user_external_id")
                                .numberType("amount_available", 100)
                                .numberValue("amount_submitted", 0))
                        .object("settlement_summary", new PactDslJsonBody()
                                .nullValue("capture_submit_time")
                                .nullValue("captured_date"))
                        .stringMatcher("payment_provider", "sandbox")
                        .booleanValue("delayed_capture", true)
                        .booleanValue("moto", false)
                        .stringValue("authorisation_mode", "web")
                        .array("links")
                        .object()
                        .stringValue("rel", "self")
                        .stringValue("method", "GET")
                        .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/chargeABCDE1234")
                        .closeObject()
                        .object()
                        .stringValue("rel", "refunds")
                        .stringValue("method", "GET")
                        .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/chargeABCDE1234/refunds")
                        .closeObject()
                        .object()
                        .stringValue("rel", "next_url")
                        .stringValue("method", "GET")
                        .stringMatcher("href", "http.*://.*/secure/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        .closeObject()
                        .object()
                        .stringValue("rel", "next_url_post")
                        .stringValue("method", "POST")
                        .stringValue("type", "application/x-www-form-urlencoded")
                        .stringMatcher("href", "http.*://.*/secure")
                        .object("params", new PactDslJsonBody()
                                .stringType("chargeTokenId", "ae749781-6562-4e0e-8f56-32d9639079dc"))
                        .closeObject()
                        .closeArray()
                ))
                .toPact();

    }

    @Test
    @PactVerification(fragment = "getAChargeRequestForACreatedPayment")
    public void getCreatedPayment_shouldSerialiseConnectorChargeCorrectly() {
        String chargeId = "chargeABCDE1234";
        Account account = new Account(ACCOUNT_ID, TokenPaymentType.CARD, "a-token-link");

        PaymentWithAllLinks paymentResponse = getPaymentService.getPayment(account, chargeId);

        assertThat(paymentResponse.getAmount(), is(100L));
        assertThat(paymentResponse.getState(), is(new PaymentState("created", false)));
        assertThat(paymentResponse.getDescription(), is("some description"));
        assertThat(paymentResponse.getReference(), is("some-reference"));
        assertThat(paymentResponse.getLanguage(), is(SupportedLanguage.ENGLISH));
        assertThat(paymentResponse.getPaymentId(), is(chargeId));
        assertThat(paymentResponse.getReturnUrl().get(), is("https://somewhere.gov.uk/rainbow/1"));
        assertThat(paymentResponse.getPaymentProvider(), is("sandbox"));
        assertThat(paymentResponse.getCreatedDate(), is("2020-02-13T16:26:04.204Z"));
        assertThat(paymentResponse.getMoto(), is (false));
        assertThat(paymentResponse.getDelayedCapture(), is(true));
        assertThat(paymentResponse.getCorporateCardSurcharge(), is(Optional.empty()));
        assertThat(paymentResponse.getTotalAmount(), is(Optional.empty()));
        assertThat(paymentResponse.getAuthorisationMode(), is(AuthorisationMode.WEB));
        assertThat(paymentResponse.getLinks().getSelf().getHref(), containsString("v1/payments/" + chargeId));
        assertThat(paymentResponse.getLinks().getSelf().getMethod(), is("GET"));
        assertThat(paymentResponse.getLinks().getRefunds().getHref(), containsString("v1/payments/" + chargeId + "/refunds"));
        assertThat(paymentResponse.getLinks().getRefunds().getMethod(), is("GET"));
        assertThat(paymentResponse.getLinks().getNextUrl().getHref().matches("http.*://.*/secure/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), is(true));
        assertThat(paymentResponse.getLinks().getNextUrl().getMethod(), is("GET"));
        assertThat(paymentResponse.getLinks().getNextUrlPost().getMethod(), is("POST"));
        assertThat(paymentResponse.getLinks().getNextUrlPost().getHref(), containsString("/secure"));
        assertThat(paymentResponse.getLinks().getNextUrlPost().getParams().size(), is(1));
        assertThat(paymentResponse.getLinks().getNextUrlPost().getParams().get("chargeTokenId").toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), is(true));
        assertThat(paymentResponse.getLinks().getCapture(), is(nullValue()));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact getPaymentWithWalletType(PactDslWithProvider builder) {
        return builder
                .given("a charge with wallet type APPLE_PAY exists",
                        Map.of("gateway_account_id", ACCOUNT_ID,
                                "charge_id", "charge_apple12345"))
                .uponReceiving("get a charge request for a wallet payment - connector")
                .path("/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge_apple12345")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .numberType("amount", 100L)
                        .object("state", new PactDslJsonBody()
                                .stringValue("status", "success")
                                .booleanType("finished", true))
                        .stringValue("charge_id", "charge_apple12345")
                        .stringValue("payment_provider", "sandbox")
                        .object("card_details", new PactDslJsonBody()
                                .stringValue("cardholder_name", "aName"))
                        .stringValue("authorisation_mode", "web")
                        .stringType("wallet_type", "APPLE_PAY")
                )
                .toPact();
    }

    @Test
    @PactVerification(fragment = "getPaymentWithWalletType")
    public void getPaymentWithWalletType_shouldSerialiseConnectorChargeCorrectly() {
        String chargeId = "charge_apple12345";
        Account account = new Account(ACCOUNT_ID, TokenPaymentType.CARD, "a-token-link");

        PaymentWithAllLinks paymentResponse = getPaymentService.getPayment(account, chargeId);

        assertThat(paymentResponse.getAmount(), is(100L));
        assertThat(paymentResponse.getState(), is(new PaymentState("success", true)));
        assertThat(paymentResponse.getPaymentId(), is(chargeId));
        assertThat(paymentResponse.getPaymentProvider(), is("sandbox"));
        assertThat(paymentResponse.getAuthorisationMode(), is(AuthorisationMode.WEB));
        assertThat(paymentResponse.getCardDetails().get().getCardHolderName(), is("aName"));
        assertThat(paymentResponse.getCardDetails().get().getWalletType().get(), is(Wallet.APPLE_PAY.getTitleCase()));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact getPaymentWithFeeAndNetAmount(PactDslWithProvider builder) {
        return builder
                .given("a charge with fee and net_amount exists",
                        Map.of("gateway_account_id", ACCOUNT_ID,
                                "charge_id", "charge_fee12345"))
                .uponReceiving("get charge which has a fee and a net amount")
                .path("/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge_fee12345")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(Objects.requireNonNull(new PactDslJsonBody()
                        .numberType("amount", 100)
                        .numberType("fee", 5)
                        .numberType("net_amount", 345)
                        .object("state", new PactDslJsonBody()
                                .stringMatcher("status", "success")
                                .booleanValue("finished", true))
                        .stringValue("charge_id", "charge_fee12345")
                        .stringType("description", "Test description")
                        .stringType("reference", "aReference")
                        .stringValue("language", "en")
                        .stringType("gateway_transaction_id", "gateway-tx-123456")
                        .stringType("return_url", "https://somewhere.gov.uk/rainbow/1")
                        .stringValue("payment_provider", "sandbox")
                        .datetime("created_date", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                Date.from(Instant.parse("2020-02-13T16:26:04.204Z")))
                        .object("refund_summary", new PactDslJsonBody()
                                .stringValue("status", "available")
                                .nullValue("user_external_id")
                                .numberValue("amount_submitted", 0)
                                .numberType("amount_available", 0))
                        .object("settlement_summary", new PactDslJsonBody()
                                .nullValue("capture_submit_time")
                                .nullValue("captured_date"))
                        .booleanValue("delayed_capture", false)
                        .booleanValue("moto", false)
                        .array("links")
                        .object()
                        .stringValue("rel", "self")
                        .stringValue("method", "GET")
                        .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge_fee12345")
                        .closeObject()
                        .object()
                        .stringValue("rel", "refunds")
                        .stringValue("method", "GET")
                        .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge_fee12345/refunds")
                        .closeObject()
                        .closeArray())
                )
                .toPact();
    }

    @Test
    @PactVerification(fragment = "getPaymentWithFeeAndNetAmount")
    public void getPaymentWithFeeAndNetAmount_shouldSerialiseConnectorChargeCorrectly() {
        String chargeId = "charge_fee12345";
        Account account = new Account(ACCOUNT_ID, TokenPaymentType.CARD, "a-token-link");
        PaymentWithAllLinks paymentResponse = getPaymentService.getPayment(account, chargeId);

        assertThat(paymentResponse.getProviderId(), is("gateway-tx-123456"));
        assertThat(paymentResponse.getFee().get(), is(5L));
        assertThat(paymentResponse.getNetAmount().get(), is(345L));
        assertThat(paymentResponse.getLinks().getNextUrl(), is(nullValue()));
        assertThat(paymentResponse.getLinks().getNextUrlPost(), is(nullValue()));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact getRejectedRecurringPaymentWithCanRetryTrue(PactDslWithProvider builder) {
        return builder
                .given("a gateway account and an active agreement exists",
                        Map.of("gateway_account_id", ACCOUNT_ID,
                                "agreement_external_id", "abcdefghijklmnopqrstuvwxyz"))
                .given("a charge with authorisation mode agreement and rejected status exists",
                        Map.of("agreement_external_id", "abcdefghijklmnopqrstuvwxyz",
                                "charge_external_id", "charge_rcp12345f",
                                "amount", "1968"))
                .uponReceiving("get a charge request for a rejected rcp payment")
                .path("/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge_rcp12345f")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(Objects.requireNonNull(new PactDslJsonBody()
                        .numberType("amount", 100)
                        .object("state", new PactDslJsonBody()
                                .stringValue("status", "failed")
                                .booleanValue("finished", true)
                                .stringValue("code", "P0010")
                                .stringValue("message", "Payment method rejected")
                                .booleanValue("can_retry", true))
                        .stringValue("charge_id", "charge_rcp12345f")
                        .stringType("description", "Test description")
                        .stringType("reference", "aReference")
                        .stringValue("language", "en")
                        .stringType("return_url", "https://somewhere.gov.uk/rainbow/1")
                        .stringValue("payment_provider", "sandbox")
                        .datetime("created_date", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                Date.from(Instant.parse("2020-02-13T16:26:04.204Z")))
                        .booleanValue("delayed_capture", false)
                        .booleanValue("moto", false)
                        .stringValue("authorisation_mode", "agreement")
                        .array("links")
                            .object()
                                .stringValue("rel", "self")
                                .stringValue("method", "GET")
                                .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge_rcp12345f")
                            .closeObject()
                            .object()
                                .stringValue("rel", "refunds")
                                .stringValue("method", "GET")
                                .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/charge_rcp12345f/refunds")
                            .closeObject()
                        .closeArray())
                )
                .toPact();
    }

    @Test
    @PactVerification(fragment = "getRejectedRecurringPaymentWithCanRetryTrue")
    public void getRejectedRecurringPaymentWithCanRetryTrue_shouldSerialiseConnectorChargeCorrectly() {
        String chargeId = "charge_rcp12345f";
        Account account = new Account(ACCOUNT_ID, TokenPaymentType.CARD, "a-token-link");
        PaymentWithAllLinks paymentResponse = getPaymentService.getPayment(account, chargeId);

        assertThat(paymentResponse.getState().getCanRetry(), is(true));
        assertThat(paymentResponse.getAuthorisationMode(), is(AuthorisationMode.AGREEMENT));
        assertThat(paymentResponse.getLinks().getNextUrl(), is(nullValue()));
        assertThat(paymentResponse.getLinks().getNextUrlPost(), is(nullValue()));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact getMotoApiPayment(PactDslWithProvider builder) {
        return builder
                .given("a charge with authorisation mode moto_api exists",
                        Map.of("gateway_account_id", ACCOUNT_ID,
                                "charge_id", "ch_moto12345f"))
                .uponReceiving("get a charge request for a moto_api created payment")
                .path("/v1/api/accounts/" + ACCOUNT_ID + "/charges/ch_moto12345f")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(Objects.requireNonNull(new PactDslJsonBody()
                        .numberType("amount", 100)
                        .object("state", new PactDslJsonBody()
                                .stringValue("status", "created")
                                .booleanValue("finished", false))
                        .stringValue("charge_id", "ch_moto12345f")
                        .stringType("description", "a description")
                        .stringType("reference", "a reference")
                        .stringValue("language", "en")
                        .stringType("return_url", "https://somewhere.gov.uk/rainbow/1")
                        .stringValue("payment_provider", "sandbox")
                        .datetime("created_date", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                Date.from(Instant.parse("2020-02-13T16:26:04.204Z")))
                        .booleanType("delayed_capture", false)
                        .booleanValue("moto", true)
                        .stringValue("authorisation_mode", "moto_api")
                        .array("links")
                            .object()
                                .stringValue("rel", "self")
                                .stringValue("method", "GET")
                                .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/ch_moto12345f")
                            .closeObject()
                            .object()
                                .stringValue("rel", "refunds")
                                .stringValue("method", "GET")
                                .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/ch_moto12345f/refunds")
                            .closeObject()
                            .object()
                                .stringValue("rel", "auth_url_post")
                                .stringValue("type", "application/json")
                                .stringMatcher("href", "http.*://.*v1/api/charges/authorise")
                                .stringValue("method", "POST")
                                .object("params", new PactDslJsonBody()
                                    .stringType("one_time_token", "token_1234567asdf"))
                            .closeObject()
                        .closeArray())
                )
                .toPact();
    }

    @Test
    @PactVerification(fragment = "getMotoApiPayment")
    public void getMotoApiPayment_shouldSerialiseConnectorChargeCorrectly() {
        String chargeId = "ch_moto12345f";
        Account account = new Account(ACCOUNT_ID, TokenPaymentType.CARD, "a-token-link");
        PaymentWithAllLinks paymentResponse = getPaymentService.getPayment(account, chargeId);

        assertThat(paymentResponse.getAmount(), is(100L));
        assertThat(paymentResponse.getState(), is(new PaymentState("created", false)));
        assertThat(paymentResponse.getDescription(), is("a description"));
        assertThat(paymentResponse.getReference(), is("a reference"));
        assertThat(paymentResponse.getPaymentId(), is(chargeId));
        assertThat(paymentResponse.getMoto(), is (true));
        assertThat(paymentResponse.getAuthorisationMode(), is(AuthorisationMode.MOTO_API));
        assertThat(paymentResponse.getLinks().getSelf().getHref(), containsString("v1/payments/" + chargeId));
        assertThat(paymentResponse.getLinks().getSelf().getMethod(), is("GET"));
        assertThat(paymentResponse.getLinks().getRefunds().getHref(), containsString("v1/payments/" + chargeId + "/refunds"));
        assertThat(paymentResponse.getLinks().getRefunds().getMethod(), is("GET"));
        assertThat(paymentResponse.getLinks().getAuthUrlPost(), is(new PostLink("http://publicapi.test.localhost/v1/auth", "POST", "application/json", Collections.singletonMap("one_time_token", "token_1234567asdf"))));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact getPaymentWithExemptionFromConnector(PactDslWithProvider builder) {
        return builder
                .given("a charge with honoured corporate exemption exists",
                        Map.of("gateway_account_id", ACCOUNT_ID,
                                "charge_id", "ch_exemption12345"))
                .uponReceiving("get charge which has an honoured corporate exemption")
                .path("/v1/api/accounts/" + ACCOUNT_ID + "/charges/ch_exemption12345")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(Objects.requireNonNull(new PactDslJsonBody()
                        .object("state", new PactDslJsonBody()
                                .stringMatcher("status", "success")
                                .booleanValue("finished", true))
                        .numberType("amount", 100)
                        .numberType("fee", 5)
                        .numberType("net_amount", 345)
                        .datetime("created_date", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                Date.from(Instant.parse("2020-02-13T16:26:04.204Z")))
                        .stringValue("charge_id", "ch_exemption12345")
                        .stringValue("language", "en")
                        .stringType("description")
                        .stringType("reference")
                        .stringType("gateway_transaction_id", "gateway-tx-123456")
                        .stringType("return_url", "https://somewhere.gov.uk/rainbow/1")
                        .stringValue("payment_provider", "sandbox")
                        .object("refund_summary", new PactDslJsonBody()
                                .stringValue("status", "available")
                                .nullValue("user_external_id")
                                .numberType("amount_available", 350)
                                .numberType("amount_submitted", 0))
                        .object("settlement_summary", new PactDslJsonBody()
                                .nullValue("capture_submit_time")
                                .nullValue("captured_date"))
                        .booleanValue("delayed_capture", false)
                        .booleanValue("moto", false)
                        .object("exemption", new PactDslJsonBody()
                                .booleanValue("requested", true)
                                .stringValue("type", "corporate")
                                .object("outcome", new PactDslJsonBody()
                                        .stringValue("result", "honoured")))
                        .array("links")
                            .object()
                                .stringValue("rel", "self")
                                .stringValue("method", "GET")
                                .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/ch_exemption12345")
                            .closeObject()
                            .object()
                                .stringValue("rel", "refunds")
                                .stringValue("method", "GET")
                                .stringMatcher("href", "http.*://.*/v1/api/accounts/" + ACCOUNT_ID + "/charges/ch_exemption12345/refunds")
                            .closeObject()
                        .closeArray()))
                .toPact();
    }

    @Test
    @PactVerification(fragment = "getPaymentWithExemptionFromConnector")
    public void getPaymentWithExemptionFromConnector_shouldSerialiseConnectorChargeCorrectly() {
        String chargeId = "ch_exemption12345";
        Account account = new Account(ACCOUNT_ID, TokenPaymentType.CARD, "a-token-link");

        PaymentWithAllLinks paymentResponse = getPaymentService.getPayment(account, chargeId);

        assertThat(paymentResponse.getExemption(), is(notNullValue()));
        assertThat(paymentResponse.getExemption().getRequested(), is(true));
        assertThat(paymentResponse.getExemption().getType(), is("corporate"));
        assertThat(paymentResponse.getExemption().getOutcome().getResult(), is("honoured"));
    }
}
