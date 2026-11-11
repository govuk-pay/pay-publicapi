package uk.gov.pay.api.service;

import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit.PactProviderRule;
import au.com.dius.pact.consumer.junit.PactVerification;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.jayway.jsonassert.JsonAssert;
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
import uk.gov.pay.api.exception.SearchPaymentsException;
import uk.gov.pay.api.ledger.service.LedgerUriGenerator;
import uk.gov.pay.api.model.TokenPaymentType;
import uk.gov.pay.api.model.search.PaginationDecorator;
import uk.gov.service.payments.commons.model.AgreementPaymentType;
import uk.gov.service.payments.commons.model.AuthorisationMode;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class CardPaymentSearchServicePactTest {

    @Rule
    public PactProviderRule ledgerRule = new PactProviderRule("ledger", this);

    @Mock
    private PublicApiConfig configuration;
    private PaymentSearchService paymentSearchService;
    private static final String tokenLink = "a-token-link";

    @Before
    public void setUp() {
        when(configuration.getLedgerUrl()).thenReturn(ledgerRule.getUrl());

        when(configuration.getBaseUrl()).thenReturn("http://publicapi.test.localhost/");

        Client client = RestClientFactory.buildClient(new RestClientConfig(false));
        LedgerUriGenerator ledgerUriGenerator = new LedgerUriGenerator(configuration);
        paymentSearchService = new PaymentSearchService(
                new PublicApiUriGenerator(configuration),
                new PaginationDecorator(configuration),
                new LedgerService(client, ledgerUriGenerator));
    }
    
    @Pact(consumer = "publicapi")
    public RequestResponsePact searchTransactionsWithFirstDigitsCardNumber(PactDslWithProvider builder) {
        return builder
                .given("a payment with success state exists",
                        Map.of("transaction_external_id", "charge97837509646393d4e",
                            "gateway_account_id", "123456"))
                .uponReceiving("search payment with first digits card number")
                .path("/v1/transaction")
                .matchQuery("first_digits_card_number", "424242")
                .matchQuery("page", "1")
                .matchQuery("display_size", "500")
                .matchQuery("account_id", "123456")
                .matchQuery("transaction_type", "PAYMENT")
                .matchQuery("exact_reference_match", "true")
                .matchQuery("status_version", "1")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .numberValue("count", 1)
                        .numberValue("total", 1)
                        .numberValue("page", 1)
                        .array("results")
                            .object()
                                .numberType("amount", 1000)
                                .stringType("transaction_id", "charge97837509646393d4e")
                                .object("state")
                                    .booleanValue("finished", true)
                                    .stringValue("status", "success")
                                .closeObject()
                                .asBody()
                                .stringType("description", "Test description")
                                .stringType("reference", "aReference")
                                .stringType("email", "someone@example.org")
                                .stringType("payment_provider", "sandbox")
                                .stringMatcher("created_date", "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2}).(\\d{3})Z")
                                .object("card_details")
                                    .stringType("cardholder_name", "J Doe")
                                    .stringType("card_brand", "Visa")
                                    .stringType("last_digits_card_number", "1234")
                                    .stringType("first_digits_card_number", "654321")
                                    .stringType("expiry_date", "02/19")
                                    .object("billing_address")
                                        .stringType("line1", "line1")
                                        .stringType("line2", "line2")
                                        .stringType("postcode", "AB1 2CD")
                                        .stringType("city", "London")
                                        .stringType("country", "GB")
                                    .closeObject()
                                    .asBody()
                                .closeObject()
                                .asBody()
                            .closeObject()
                        .closeArray()
                        .asBody()
                        .object("_links")
                            .object("self")
                                .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                        "&first_digits_card_number=424242&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                            .object("last_page")
                                .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                        "&first_digits_card_number=424242&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                            .object("first_page")
                                .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                        "&first_digits_card_number=424242&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                        .closeObject()
                        .asBody()
                )
                .toPact();
    }

    @Test
    @PactVerification(fragment = "searchTransactionsWithFirstDigitsCardNumber")
    public void searchTransactionsWithFirstDigitsCardNumber() {
        Account account = new Account("123456", TokenPaymentType.CARD, tokenLink);
        var searchParams = new PaymentSearchParams.Builder()
                .withFirstDigitsCardNumber("424242")
                .withPageNumber("1")
                .withDisplaySize("500")
                .build();
        Response response = paymentSearchService.searchLedgerPayments(account, searchParams);
        JsonAssert.with(response.getEntity().toString())
                .assertThat("count", is(1))
                .assertThat("total", is(1))
                .assertThat("page", is(1))
                .assertThat("results", hasSize(equalTo(1)))
                .assertThat("results[0]", hasKey("amount"))
                .assertThat("results[0]", hasKey("state"))
                .assertThat("results[0]", hasKey("reference"))
                .assertThat("results[0]", hasKey("email"))
                .assertThat("results[0].card_details.cardholder_name", is("J Doe"))
                .assertThat("results[0].card_details", hasKey("first_digits_card_number"))
                .assertThat("results[0].card_details", hasKey("last_digits_card_number"))
                .assertThat("results[0].state", hasKey("status"))
                .assertThat("results[0].state", hasKey("finished"));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact searchTransactionsWithLastDigitsCardNumber(PactDslWithProvider builder) {
        return builder
                .given("a payment with success state exists",
                        Map.of("transaction_external_id", "charge97837509646393e3C",
                                "gateway_account_id", "123456"))
                .uponReceiving("search payment with last digit card number")
                .path("/v1/transaction")
                .matchQuery("last_digits_card_number", "4242")
                .matchQuery("page", "1")
                .matchQuery("display_size", "500")
                .matchQuery("account_id", "123456")
                .matchQuery("transaction_type", "PAYMENT")
                .matchQuery("exact_reference_match", "true")
                .matchQuery("status_version", "1")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .numberValue("count", 1)
                        .numberValue("total", 1)
                        .numberValue("page", 1)
                        .array("results")
                            .object()
                                .numberType("amount", 1000)
                                .stringType("transaction_id", "charge97837509646393e3C")
                                .object("state")
                                    .booleanValue("finished", true)
                                    .stringValue("status", "success")
                                .closeObject()
                                .asBody()
                                .stringType("description", "Test description")
                                .stringType("reference", "aReference")
                                .stringType("email", "someone@example.org")
                                .stringType("payment_provider", "sandbox")
                                .stringMatcher("created_date", "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2}).(\\d{3})Z")
                                .object("card_details")
                                    .stringType("cardholder_name", "J Doe")
                                    .stringType("card_brand", "Visa")
                                    .stringType("last_digits_card_number", "1234")
                                    .stringType("first_digits_card_number", "654321")
                                    .stringType("expiry_date", "02/19")
                                    .object("billing_address")
                                    .stringType("line1", "line1")
                                    .stringType("line2", "line2")
                                    .stringType("postcode", "AB1 2CD")
                                    .stringType("city", "London")
                                    .stringType("country", "GB")
                                    .closeObject()
                                    .asBody()
                                .closeObject()
                                .asBody()
                            .closeObject()
                        .closeArray()
                        .asBody()
                        .object("_links")
                            .object("self")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&last_digits_card_number=4242&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                            .object("last_page")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&last_digits_card_number=4242&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                            .object("first_page")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&last_digits_card_number=4242&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                        .closeObject()
                        .asBody()
                )
                .toPact();
    }

    @Test
    @PactVerification(fragment = "searchTransactionsWithLastDigitsCardNumber")
    public void searchTransactionsWithLastDigitsCardNumber() {
        Account account = new Account("123456", TokenPaymentType.CARD, tokenLink);
        var searchParams = new PaymentSearchParams.Builder()
                .withLastDigitsCardNumber("4242")
                .withPageNumber("1")
                .withDisplaySize("500")
                .build();
        Response response = paymentSearchService.searchLedgerPayments(account, searchParams);
        JsonAssert.with(response.getEntity().toString())
                .assertThat("count", is(1))
                .assertThat("total", is(1))
                .assertThat("page", is(1))
                .assertThat("results", hasSize(equalTo(1)))
                .assertThat("results[0]", hasKey("amount"))
                .assertThat("results[0]", hasKey("state"))
                .assertThat("results[0]", hasKey("reference"))
                .assertThat("results[0]", hasKey("email"))
                .assertThat("results[0].card_details.cardholder_name", is("J Doe"))
                .assertThat("results[0].card_details", hasKey("first_digits_card_number"))
                .assertThat("results[0].card_details", hasKey("last_digits_card_number"))
                .assertThat("results[0].state", hasKey("status"))
                .assertThat("results[0].state", hasKey("finished"));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact searchTransactionsWithStateInSuccessState(PactDslWithProvider builder) {
        return builder
                .given("a payment with success state exists",
                        Map.of("transaction_external_id", "charge97837509646393f5r",
                                "gateway_account_id", "123456"))
                .uponReceiving("search payment with email")
                .path("/v1/transaction")
                .matchQuery("state", "success")
                .matchQuery("page", "1")
                .matchQuery("display_size", "500")
                .matchQuery("account_id", "123456")
                .matchQuery("transaction_type", "PAYMENT")
                .matchQuery("exact_reference_match", "true")
                .matchQuery("status_version", "1")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .numberValue("count", 1)
                        .numberValue("total", 1)
                        .numberValue("page", 1)
                        .array("results")
                            .object()
                                .numberType("amount", 1000)
                                .stringType("transaction_id", "charge97837509646393f5r")
                                .object("state")
                                    .booleanValue("finished", true)
                                    .stringValue("status", "success")
                                .closeObject()
                                .asBody()
                                .stringType("description", "Test description")
                                .stringType("reference", "aReference")
                                .stringType("email", "j.doe@example.org")
                                .stringType("payment_provider", "sandbox")
                                .stringMatcher("created_date", "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2}).(\\d{3})Z")
                                .stringType("authorisation_mode", "web")
                                .asBody()
                                .object("card_details")
                                    .stringType("cardholder_name", "J Doe")
                                    .stringType("card_brand", "Visa")
                                    .stringType("last_digits_card_number", "1234")
                                    .stringType("first_digits_card_number", "654321")
                                    .stringType("expiry_date", "02/19")
                                .object("billing_address")
                                    .stringType("line1", "line1")
                                    .stringType("line2", "line2")
                                    .stringType("postcode", "AB1 2CD")
                                    .stringType("city", "London")
                                    .stringType("country", "GB")
                                .closeObject()
                                .asBody()
                                .closeObject()
                                .asBody()
                            .closeObject()
                        .closeArray()
                        .asBody()
                        .object("_links")
                            .object("self")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&state=success&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                            .object("last_page")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&state=success&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                            .object("first_page")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&state=success&transaction_type=PAYMENT&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                        .closeObject()
                        .asBody()
                )
                .toPact();
    }

    @Test
    @PactVerification(fragment = "searchTransactionsWithStateInSuccessState")
    public void searchTransactionsWithStateInSuccessState() {
        Account account = new Account("123456", TokenPaymentType.CARD, tokenLink);
        var searchParams = new PaymentSearchParams.Builder()
                .withState("success")
                .withPageNumber("1")
                .withDisplaySize("500")
                .build();

        Response response = paymentSearchService.searchLedgerPayments(account, searchParams);
        JsonAssert.with(response.getEntity().toString())
                .assertThat("count", is(1))
                .assertThat("total", is(1))
                .assertThat("page", is(1))
                .assertThat("results", hasSize(equalTo(1)))
                .assertThat("results[0]", hasKey("amount"))
                .assertThat("results[0]", hasKey("state"))
                .assertThat("results[0]", hasKey("reference"))
                .assertThat("results[0].email", is("j.doe@example.org"))
                .assertThat("results[0].card_details.cardholder_name", is("J Doe"))
                .assertThat("results[0].card_details", hasKey("first_digits_card_number"))
                .assertThat("results[0].card_details", hasKey("last_digits_card_number"))
                .assertThat("results[0].state", hasKey("status"))
                .assertThat("results[0].state", hasKey("finished"))
                .assertThat("results[0].authorisation_mode", is("web"));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact shouldReturn404WhenSearchingWithNonExistentPageNumber(PactDslWithProvider builder) {
        return builder
                .given("a payment with success state exists",
                        Map.of("transaction_external_id", "charge97837509646393f5r",
                                "gateway_account_id", "123456"))
                .uponReceiving("search payment with email")
                .path("/v1/transaction")
                .matchQuery("page", "999")
                .matchQuery("display_size", "500")
                .matchQuery("account_id", "123456")
                .matchQuery("transaction_type", "PAYMENT")
                .matchQuery("exact_reference_match", "true")
                .matchQuery("status_version", "1")
                .method("GET")
                .willRespondWith()
                .status(404)
                .toPact();
    }

    @Test
    @PactVerification(fragment = "shouldReturn404WhenSearchingWithNonExistentPageNumber")
    public void shouldReturn404WhenSearchingWithNonExistentPageNumber() {
        Account account = new Account("123456", TokenPaymentType.CARD, tokenLink);
        var searchParams = new PaymentSearchParams.Builder()
                .withDisplaySize("500")
                .withPageNumber("999")
                .build();

        SearchPaymentsException searchPaymentsException = assertThrows(SearchPaymentsException.class,
                () -> paymentSearchService.searchLedgerPayments(account, searchParams));

        assertThat(searchPaymentsException, hasProperty("errorStatus", is(404)));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact shouldReturnAPaymentWhenSearchedBySettledDates(PactDslWithProvider builder) {
        return builder
                .given("three payments with payout dates exists",
                        Map.of("gateway_account_id", "123456"))
                .uponReceiving("Return a payment, filtering by from_settled_date and to date_settled_date")
                .path("/v1/transaction")
                .matchQuery("from_settled_date", "2020-09-19")
                .matchQuery("to_settled_date", "2020-09-20")
                .matchQuery("page", "1")
                .matchQuery("display_size", "500")
                .matchQuery("account_id", "123456")
                .matchQuery("transaction_type", "PAYMENT")
                .matchQuery("exact_reference_match", "true")
                .matchQuery("status_version", "1")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .numberValue("count", 1)
                        .numberValue("total", 1)
                        .numberValue("page", 1)
                            .array("results")
                                .object()
                                    .numberType("amount", 1000)
                                    .stringType("transaction_id", "charge97837509646393f5r")
                                    .object("state")
                                        .booleanType("finished", true)
                                        .stringType("status", "success")
                                    .closeObject()
                                    .asBody()
                                    .object("settlement_summary")
                                        .date("settled_date", "yyyy-MM-dd")
                                    .closeObject()
                                    .asBody()
                                .closeObject()
                            .closeArray()
                            .asBody()
                            .object("_links")
                                .object("self")
                                .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                        "&transaction_type=PAYMENT&from_settled_date=2020-09-19&to_settled_date=2020-09-20&page=1&display_size=500")
                                .closeObject()
                            .asBody()
                                .object("last_page")
                                .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                        "&transaction_type=PAYMENT&from_settled_date=2020-09-19&to_settled_date=2020-09-20&page=1&display_size=500")
                                .closeObject()
                            .asBody()
                                .object("first_page")
                                .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                        "&transaction_type=PAYMENT&from_settled_date=2020-09-19&to_settled_date=2020-09-20&page=1&display_size=500")
                                .closeObject()
                                .asBody()
                            .closeObject()
                            .asBody())
                .toPact();
    }

    @Test
    @PactVerification(fragment = "shouldReturnAPaymentWhenSearchedBySettledDates")
    public void shouldReturnAPaymentWhenSearchedBySettledDates() {
        String accountId = "123456";
        Account account = new Account(accountId, TokenPaymentType.CARD, tokenLink);
        var searchParams = new PaymentSearchParams.Builder()
                .withFromSettledDate("2020-09-19")
                .withToSettledDate("2020-09-20")
                .withDisplaySize("500")
                .withPageNumber("1")
                .build();
        Response response = paymentSearchService.searchLedgerPayments(account, searchParams);
        JsonAssert.with(response.getEntity().toString())
                .assertThat("count", is(1))
                .assertThat("total", is(1))
                .assertThat("page", is(1))
                .assertThat("results", hasSize(equalTo(1)))
                .assertThat("results[0].settlement_summary", hasKey("settled_date"))
                .assertThat("results[0].settlement_summary.settled_date", matchesPattern("^(\\d{4})-(\\d{2})-(\\d{2})"));
    }
    
    @Pact(consumer = "publicapi")
    public RequestResponsePact searchedByAgreementId(PactDslWithProvider builder) {
        return builder
                .given("a recurring card payment exists for agreement",
                        Map.of("account_id", "123456",
                                "agreement_id", "agreement-1"))
                .uponReceiving("search payment by agreement id")
                .path("/v1/transaction")
                .matchQuery("agreement_id", "agreement-1")
                .matchQuery("page", "1")
                .matchQuery("display_size", "500")
                .matchQuery("account_id", "123456")
                .matchQuery("transaction_type", "PAYMENT")
                .matchQuery("exact_reference_match", "true")
                .matchQuery("status_version", "1")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .numberValue("count", 1)
                        .numberValue("total", 1)
                        .numberValue("page", 1)
                        .array("results")
                            .object()
                                .numberType("amount", 1000)
                                .stringType("transaction_id", "charge97837509646393f5r")
                                .object("state")
                                    .booleanType("finished", true)
                                    .stringType("status", "success")
                                .closeObject()
                                .asBody()
                                .stringType("authorisation_mode", "agreement")
                                .stringType("agreement_payment_type", "recurring")
                            .closeObject()
                        .closeArray()
                        .asBody()
                        .object("_links")
                            .object("self")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&transaction_type=PAYMENT&agreement_id=agreement-1&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                            .object("last_page")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&transaction_type=PAYMENT&agreement_id=agreement-1&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                            .object("first_page")
                            .matchUrl("href","http://ledger.service.backend", "v1", "transaction?account_id=123456" +
                                    "&transaction_type=PAYMENT&agreement_id=agreement-1&page=1&display_size=500")
                            .closeObject()
                            .asBody()
                        .closeObject()
                        .asBody())
                .toPact();
    }

    @Test
    @PactVerification(fragment = "searchedByAgreementId")
    public void searchedByAgreementId() {
        String accountId = "123456";
        String agreementId = "agreement-1";
        Account account = new Account(accountId, TokenPaymentType.CARD, tokenLink);
        var searchParams = new PaymentSearchParams.Builder()
                .withAgreementId(agreementId)
                .withDisplaySize("500")
                .withPageNumber("1")
                .build();
        Response response = paymentSearchService.searchLedgerPayments(account, searchParams);
        JsonAssert.with(response.getEntity().toString())
                .assertThat("count", is(1))
                .assertThat("total", is(1))
                .assertThat("page", is(1))
                .assertThat("results", hasSize(equalTo(1)))
                .assertThat("results[0].authorisation_mode", is(AuthorisationMode.AGREEMENT.getName()))
                .assertThat("results[0].agreement_payment_type", is(AgreementPaymentType.RECURRING.getName()));
    }

    @Pact(consumer = "publicapi")
    public RequestResponsePact searchPaymentInSuccessStateThatHasHonouredExemption(PactDslWithProvider builder) {
        return builder
                .given("a transaction with honoured corporate exemption exists",
                        Map.of("gateway_account_id", "123456",
                                "transaction_external_id", "charge97837509646397y4"))
                .uponReceiving("search payment in success state that has honoured exemption")
                .path("/v1/transaction")
                .matchQuery("state", "success")
                .matchQuery("page", "1")
                .matchQuery("display_size", "500")
                .matchQuery("account_id", "123456")
                .matchQuery("transaction_type", "PAYMENT")
                .matchQuery("exact_reference_match", "true")
                .matchQuery("status_version", "1")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .numberValue("count", 1)
                        .numberValue("total", 1)
                        .numberValue("page", 1)
                        .array("results")
                            .object()
                                .numberType("amount", 1000)
                                .stringType("transaction_id", "charge97837509646393f5r")
                                .object("exemption")
                                    .booleanType("requested", true)
                                    .stringType("type", "corporate")
                                    .object("outcome")
                                        .stringValue("result", "honoured")
                                    .closeObject()
                                    .asBody()
                                .closeObject()
                                .asBody()
                                .object("state")
                                    .booleanType("finished", true)
                                    .stringType("status", "success")
                                .closeObject()
                                .asBody()
                            .closeObject()
                        .closeArray()
                ).toPact();
    }

    @Test
    @PactVerification(fragment = "searchPaymentInSuccessStateThatHasHonouredExemption")
    public void searchPaymentInSuccessStateThatHasHonouredExemption() {
        Account account = new Account("123456", TokenPaymentType.CARD, tokenLink);
        var searchParams = new PaymentSearchParams.Builder()
                .withState("success")
                .withPageNumber("1")
                .withDisplaySize("500")
                .build();
        Response response = paymentSearchService.searchLedgerPayments(account, searchParams);
        JsonAssert.with(response.getEntity().toString())
                .assertThat("count", is(1))
                .assertThat("total", is(1))
                .assertThat("page", is(1))
                .assertThat("results", hasSize(equalTo(1)))
                .assertThat("results[0].exemption", is(notNullValue()))
                .assertThat("results[0].exemption.requested", is(true))
                .assertThat("results[0].exemption.type", is("corporate"))
                .assertThat("results[0].exemption.outcome.result", is("honoured"));
    }
}
