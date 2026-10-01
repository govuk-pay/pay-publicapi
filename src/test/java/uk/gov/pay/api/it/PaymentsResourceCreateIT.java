package uk.gov.pay.api.it;

import com.jayway.jsonassert.JsonAssert;
import io.restassured.response.ValidatableResponse;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import uk.gov.pay.api.model.Address;
import uk.gov.pay.api.model.CardDetailsFromResponse;
import uk.gov.pay.api.model.PaymentState;
import uk.gov.pay.api.model.RefundSummary;
import uk.gov.pay.api.utils.JsonStringBuilder;
import uk.gov.pay.api.utils.PublicAuthMockClientJUnit5;
import uk.gov.pay.api.utils.mocks.ConnectorMockClientJUnit5;
import uk.gov.pay.api.utils.mocks.CreateChargeRequestParams;
import uk.gov.service.payments.commons.model.AgreementPaymentType;
import uk.gov.service.payments.commons.model.AuthorisationMode;
import uk.gov.service.payments.commons.model.SupportedLanguage;
import uk.gov.service.payments.commons.validation.DateTimeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static uk.gov.pay.api.model.TokenPaymentType.CARD;
import static uk.gov.pay.api.utils.Urls.paymentLocationFor;
import static uk.gov.pay.api.utils.mocks.ChargeResponseFromConnector.ChargeResponseFromConnectorBuilder.aCreateOrGetChargeResponseFromConnector;
import static uk.gov.pay.api.utils.mocks.CreateChargeRequestParams.CreateChargeRequestParamsBuilder.aCreateChargeRequestParams;
import static uk.gov.service.payments.commons.model.CommonDateTimeFormatters.ISO_INSTANT_MILLISECOND_PRECISION;
import static uk.gov.service.payments.commons.model.Source.CARD_PAYMENT_LINK;

public class PaymentsResourceCreateIT extends PaymentResourceITestBase {

    private static final ZonedDateTime TIMESTAMP = DateTimeUtils.toUTCZonedDateTime("2016-01-01T12:00:00Z").orElseThrow();
    private static final int AMOUNT = 9999999;
    private static final String CHARGE_ID = "ch_ab2341da231434l";
    private static final String CHARGE_TOKEN_ID = "token_1234567asdf";
    private static final PaymentState CREATED = new PaymentState("created", false, null, null);
    private static final RefundSummary REFUND_SUMMARY = new RefundSummary("pending", 100L, 50L);
    private static final String PAYMENT_PROVIDER = "Sandbox";
    private static final String CARD_BRAND_LABEL = "Mastercard";
    private static final String CARD_TYPE = "credit";
    private static final String RETURN_URL = "https://somewhere.gov.uk/rainbow/1";
    private static final String ILLEGAL_REFERENCE = "Some reference <script> alert('This is a ?{simple} XSS attack.')</script>";
    private static final String ILLEGAL_DESCRIPTION = "Some description <script> alert('This is a ?{simple} XSS attack.')</script>";
    private static final String REFERENCE = "Some reference";
    private static final String DESCRIPTION = "Some description";
    private static final String CREATED_DATE = ISO_INSTANT_MILLISECOND_PRECISION.format(TIMESTAMP);
    private static final Address BILLING_ADDRESS = new Address("line1", "line2", "NR2 5 6EG", "city", "UK");
    private static final CardDetailsFromResponse CARD_DETAILS = new CardDetailsFromResponse("1234", "123456", "Mr. Payment", "12/19", BILLING_ADDRESS, CARD_BRAND_LABEL, CARD_TYPE);
    private static final String VALID_AGREEMENT_ID = "12345678901234567890123456";
    private static final String TOO_SHORT_AGREEMENT_ID = "1234567890";
    private static final String TOO_LONG_AGREEMENT_ID = "1234567890123456789012345699999";
    private static final String SUCCESS_PAYLOAD = paymentPayload(aCreateChargeRequestParams()
            .withAmount(AMOUNT)
            .withDescription(DESCRIPTION)
            .withReference(REFERENCE)
            .withReturnUrl(RETURN_URL).build());
    private static final String GATEWAY_TRANSACTION_ID = "gateway-tx-123456";
    private final ConnectorMockClientJUnit5 connectorMockClient = new ConnectorMockClientJUnit5(connectorServer);
    private final PublicAuthMockClientJUnit5 publicAuthMockClient = new PublicAuthMockClientJUnit5(publicAuthServer);

    @Test
    void createCardPaymentWithEmptyMetadataDoesNotStoreMetadata() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        String payload = new JsonStringBuilder()
                .add("amount", 100)
                .add("reference", REFERENCE)
                .add("description", DESCRIPTION)
                .add("return_url", RETURN_URL)
                .add("metadata", Map.of())
                .build();

        connectorMockClient.respondCreated_whenCreateCharge(GATEWAY_ACCOUNT_ID, aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .build());

        postPaymentResponse(payload)
                .statusCode(201)
                .contentType(JSON)
                .body("$", not(hasKey("metadata")));
    }

    @Test
    void createCardPaymentWithMetadata() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withMetadata(Map.of("reconciled", true, "ledger_code", 123, "fuh", "fuh you"))
                .build();
        connectorMockClient.respondCreated_whenCreateCharge(GATEWAY_ACCOUNT_ID, createChargeRequestParams);

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(201)
                .contentType(JSON)
                .body("metadata.reconciled", is(true))
                .body("metadata.ledger_code", is(123))
                .body("metadata.fuh", is("fuh you"));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createChargeRequestParams);
    }

    @Test
    void createAChargeWithSetUpAgreementAndSaveAgreement() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withSetUpAgreement(VALID_AGREEMENT_ID)
                .build();
        connectorMockClient.respondCreated_whenCreateCharge(GATEWAY_ACCOUNT_ID, createChargeRequestParams);

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(HttpStatus.SC_CREATED)
                .contentType(JSON)
                .body("agreement_id", is(VALID_AGREEMENT_ID));
        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createChargeRequestParams);
    }

    @Test
    void createPayment_responseWith422_whenAgreementIdTooShort() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withSetUpAgreement(TOO_SHORT_AGREEMENT_ID)
                .build();

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
                .contentType(JSON)
                .body("field", is("set_up_agreement"))
                .body("code", is("P0102"))
                .body("description", is("Invalid attribute value: set_up_agreement. Field [set_up_agreement] length must be 26"));
    }

    @Test
    void createPayment_responseWith422_whenAgreementIdTooLong() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withSetUpAgreement(TOO_LONG_AGREEMENT_ID)
                .build();

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
                .contentType(JSON)
                .body("field", is("set_up_agreement"))
                .body("code", is("P0102"))
                .body("description", is("Invalid attribute value: set_up_agreement. Field [set_up_agreement] length must be 26"));
    }

    @Test
    void createPayment_respondWith400_whenAgreementNotFound() throws IOException {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withSetUpAgreement(VALID_AGREEMENT_ID)
                .build();
        connectorMockClient.respondAgreementNotFound_whenCreateCharge(GATEWAY_ACCOUNT_ID, VALID_AGREEMENT_ID, "Agreement with ID [%s] not found.");

        InputStream body = postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(HttpStatus.SC_BAD_REQUEST)
                .contentType(JSON).extract()
                .body().asInputStream();

        JsonAssert.with(body)
                .assertThat("$.*", hasSize(3))
                .assertThat("$.field", is("set_up_agreement"))
                .assertThat("$.code", is("P0103"))
                .assertThat("$.description", is("Invalid attribute value: set_up_agreement. Agreement ID does not exist"));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createChargeRequestParams);
    }

    @Test
    void createPayment_responseWith400_whenIncorrectAuthorisationModeForSavePaymentInstrumentToAgreement() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAuthorisationMode(AuthorisationMode.AGREEMENT)
                .withSetUpAgreement(VALID_AGREEMENT_ID)
                .build();
        connectorMockClient.respondIncorrectAuthorisationModeForSavePaymentInstrumentToAgreement_whenCreateCharge(GATEWAY_ACCOUNT_ID, VALID_AGREEMENT_ID, "error message from connector");

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(400)
                .contentType(JSON)
                .body("field", is(nullValue()))
                .body("code", is("P0104"))
                .body("description", is("Unexpected attribute: set_up_agreement"));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createChargeRequestParams);
    }

    @Test
    void createPayment_responseWith400_whenIncorrectAuthorisationModeForSavePaymentInstrumentToAgreementWithAgreementId() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAuthorisationMode(AuthorisationMode.AGREEMENT)
                .withAgreementId(VALID_AGREEMENT_ID)
                .withSetUpAgreement(VALID_AGREEMENT_ID)
                .build();
        connectorMockClient.respondIncorrectAuthorisationModeForSavePaymentInstrumentToAgreement_whenCreateCharge(GATEWAY_ACCOUNT_ID, VALID_AGREEMENT_ID, "error message from connector");

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(400)
                .contentType(JSON)
                .body("field", is(nullValue()))
                .body("code", is("P0104"))
                .body("description", is("Unexpected attribute: set_up_agreement"));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createChargeRequestParams);
    }

    @Test
    void createCardPaymentWithMetadataAsNull_shouldReturn422() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        var payload = new JSONObject()
                .put("amount", 100)
                .put("reference", "my reference")
                .put("description", "my description")
                .put("metadata", JSONObject.NULL)
                .put("return_url", "https://test.test")
                .toString();

        postPaymentResponse(payload)
                .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
                .contentType(JSON)
                .body("field", is("metadata"))
                .body("code", is("P0102"))
                .body("description", is("Invalid attribute value: metadata. Value must not be null"));
    }

    @Test
    void createCardPaymentWithPrefilledCardholderDetails() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);
        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription("description")
                .withReference("reference")
                .withReturnUrl(RETURN_URL)
                .withEmail("j.bogs@example.org")
                .witCardHolderName("J. Bogs")
                .withAddressLine1("address line 1")
                .withAddressLine2("address line 2")
                .withAddressPostcode("AB1 CD2")
                .withAddressCity("address city")
                .withAddressCountry("GB")
                .build();
        connectorMockClient.respondCreated_whenCreateCharge(GATEWAY_ACCOUNT_ID, createChargeRequestParams);

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(201)
                .contentType(JSON)
                .body("email", is("j.bogs@example.org"))
                .body("card_details.cardholder_name", is("J. Bogs"))
                .body("card_details.billing_address.line1", is("address line 1"))
                .body("card_details.billing_address.line2", is("address line 2"))
                .body("card_details.billing_address.postcode", is("AB1 CD2"))
                .body("card_details.billing_address.city", is("address city"))
                .body("card_details.billing_address.country", is("GB"));
        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createChargeRequestParams);
    }

    @Test
    void createCardPaymentShouldRespondWith400ErrorWhenNumericFieldInPrefilledCardholderDetails() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);
        String payload = new JsonStringBuilder()
                .add("amount", 1000)
                .add("reference", "reference")
                .add("description", "description")
                .add("return_url", RETURN_URL)
                .addToNestedMap("line1", 123, "prefilled_cardholder_details", "billing_address")
                .build();
        postPaymentResponse(payload)
                .statusCode(HttpStatus.SC_BAD_REQUEST)
                .contentType(JSON)
                .body("code", is("P0102"))
                .body("field", is("line1"))
                .body("description", is("Invalid attribute value: line1. Field must be a string"));
    }

    @Test
    void createCardPaymentWithSomePrefilledCardholderDetails() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);
        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription("description")
                .withReference("reference")
                .withReturnUrl(RETURN_URL)
                .witCardHolderName("J. Bogs")
                .withAddressLine1("address line 1")
                .withAddressCity("address city")
                .withAddressCountry("GB")
                .build();
        connectorMockClient.respondCreated_whenCreateCharge(GATEWAY_ACCOUNT_ID, createChargeRequestParams);

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(HttpStatus.SC_CREATED)
                .contentType(JSON)
                .body("email", is(nullValue()))
                .body("card_details.cardholder_name", is("J. Bogs"))
                .body("card_details.billing_address.line1", is("address line 1"))
                .body("card_details.billing_address.line2", is(nullValue()))
                .body("card_details.billing_address.postcode", is(nullValue()))
                .body("card_details.billing_address.city", is("address city"))
                .body("card_details.billing_address.country", is("GB"));
        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createChargeRequestParams);
    }

    @Test
    void createCardPayment() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        connectorMockClient.respondCreated_whenCreateCharge(CHARGE_TOKEN_ID, GATEWAY_ACCOUNT_ID, aCreateOrGetChargeResponseFromConnector()
                .withAmount(AMOUNT)
                .withChargeId(CHARGE_ID)
                .withState(CREATED)
                .withReturnUrl(RETURN_URL)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withPaymentProvider(PAYMENT_PROVIDER)
                .withGatewayTransactionId(GATEWAY_TRANSACTION_ID)
                .withCreatedDate(CREATED_DATE)
                .withLanguage(SupportedLanguage.ENGLISH)
                .withDelayedCapture(true)
                .withRefundSummary(REFUND_SUMMARY)
                .withCardDetails(CARD_DETAILS)
                .build());

        String responseBody = postPaymentResponse(SUCCESS_PAYLOAD)
                .statusCode(HttpStatus.SC_CREATED)
                .contentType(JSON)
                .header(HttpHeaders.LOCATION, is(paymentLocationFor(configuration.getBaseUrl(), CHARGE_ID)))
                .body("payment_id", is(CHARGE_ID))
                .body("amount", is(9999999))
                .body("reference", is(REFERENCE))
                .body("email", nullValue())
                .body("description", is(DESCRIPTION))
                .body("state.status", is(CREATED.getStatus()))
                .body("return_url", is(RETURN_URL))
                .body("payment_provider", is(PAYMENT_PROVIDER))
                .body("card_brand", is(CARD_BRAND_LABEL))
                .body("created_date", is(CREATED_DATE))
                .body("delayed_capture", is(true))
                .body("provider_id", is(GATEWAY_TRANSACTION_ID))
                .body("refund_summary.status", is("pending"))
                .body("refund_summary.amount_submitted", is(50))
                .body("refund_summary.amount_available", is(100))
                .body("_links.self.href", is(paymentLocationFor(configuration.getBaseUrl(), CHARGE_ID)))
                .body("_links.self.method", is("GET"))
                .body("_links.next_url.href", is(frontendUrlFor(CARD) + CHARGE_TOKEN_ID))
                .body("_links.next_url.method", is("GET"))
                .body("_links.next_url_post.href", is(frontendUrlFor(CARD)))
                .body("_links.next_url_post.method", is("POST"))
                .body("_links.next_url_post.type", is("application/x-www-form-urlencoded"))
                .body("_links.next_url_post.params.chargeTokenId", is(CHARGE_TOKEN_ID))
                .body("_links.events.href", is(paymentEventsLocationFor(CHARGE_ID)))
                .body("_links.events.method", is("GET"))
                .body("_links.cancel.href", is(paymentCancelLocationFor(CHARGE_ID)))
                .body("_links.cancel.method", is("POST"))
                .body("_links.refunds.href", is(paymentRefundsLocationFor(CHARGE_ID)))
                .body("_links.refunds.method", is("GET"))
                .body("metadata", nullValue())
                .extract().body().asString();

        JsonAssert.with(responseBody)
                .assertNotDefined("_links.self.type")
                .assertNotDefined("_links.self.params")
                .assertNotDefined("_links.next_url.type")
                .assertNotDefined("_links.next_url.params")
                .assertNotDefined("_links.events.type")
                .assertNotDefined("_links.events.params");

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, SUCCESS_PAYLOAD);
    }

    @Test
    void createPayment_withMinimumAmount() {
        int minimumAmount = 1;

        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondCreated_whenCreateCharge(CHARGE_TOKEN_ID, GATEWAY_ACCOUNT_ID, aCreateOrGetChargeResponseFromConnector()
                .withAmount(minimumAmount)
                .withChargeId(CHARGE_ID)
                .withState(CREATED)
                .withReturnUrl(RETURN_URL)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withPaymentProvider(PAYMENT_PROVIDER)
                .withGatewayTransactionId(GATEWAY_TRANSACTION_ID)
                .withCreatedDate(CREATED_DATE)
                .withLanguage(SupportedLanguage.ENGLISH)
                .withDelayedCapture(false)
                .withRefundSummary(REFUND_SUMMARY)
                .withCardDetails(CARD_DETAILS)
                .build());

        CreateChargeRequestParams params = aCreateChargeRequestParams()
                .withAmount(minimumAmount)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .build();

        postPaymentResponse(paymentPayload(params))
                .statusCode(201)
                .contentType(JSON)
                .body("payment_id", is(CHARGE_ID))
                .body("amount", is(minimumAmount))
                .body("reference", is(REFERENCE))
                .body("description", is(DESCRIPTION))
                .body("return_url", is(RETURN_URL))
                .body("payment_provider", is(PAYMENT_PROVIDER))
                .body("created_date", is(CREATED_DATE));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, params);
    }

    @Test
    void createMOTOPayment() {
        int amount = 1;

        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondCreated_whenCreateCharge(CHARGE_TOKEN_ID, GATEWAY_ACCOUNT_ID, aCreateOrGetChargeResponseFromConnector()
                .withAmount(amount)
                .withChargeId(CHARGE_ID)
                .withState(CREATED)
                .withReturnUrl(RETURN_URL)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withPaymentProvider(PAYMENT_PROVIDER)
                .withGatewayTransactionId(GATEWAY_TRANSACTION_ID)
                .withCreatedDate(CREATED_DATE)
                .withLanguage(SupportedLanguage.ENGLISH)
                .withDelayedCapture(false)
                .withMoto(true)
                .withRefundSummary(REFUND_SUMMARY)
                .withCardDetails(CARD_DETAILS)
                .build());

        CreateChargeRequestParams params = aCreateChargeRequestParams()
                .withAmount(amount)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withMoto(true)
                .build();

        postPaymentResponse(paymentPayload(params))
                .statusCode(201)
                .contentType(JSON)
                .body("payment_id", is(CHARGE_ID))
                .body("amount", is(amount))
                .body("reference", is(REFERENCE))
                .body("description", is(DESCRIPTION))
                .body("return_url", is(RETURN_URL))
                .body("moto", is(true))
                .body("payment_provider", is(PAYMENT_PROVIDER))
                .body("created_date", is(CREATED_DATE));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, params);
    }

    @Test
    void createPaymentWithAuthorisationModeMotoApi() {
        int amount = 1;

        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondCreated_whenCreateCharge_withAuthorisationMode_MotoApi(CHARGE_TOKEN_ID, GATEWAY_ACCOUNT_ID, aCreateOrGetChargeResponseFromConnector()
                .withAmount(amount)
                .withChargeId(CHARGE_ID)
                .withState(CREATED)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withPaymentProvider(PAYMENT_PROVIDER)
                .withGatewayTransactionId(GATEWAY_TRANSACTION_ID)
                .withCreatedDate(CREATED_DATE)
                .withLanguage(SupportedLanguage.ENGLISH)
                .withDelayedCapture(false)
                .withRefundSummary(REFUND_SUMMARY)
                .withCardDetails(CARD_DETAILS)
                .withAuthorisationMode(AuthorisationMode.MOTO_API)
                .build());

        CreateChargeRequestParams params = aCreateChargeRequestParams()
                .withAmount(amount)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAuthorisationMode(AuthorisationMode.MOTO_API)
                .build();

        postPaymentResponse(paymentPayload(params))
                .statusCode(201)
                .contentType(JSON)
                .body("$", not(hasKey("return_url")))
                .body("payment_id", is(CHARGE_ID))
                .body("amount", is(amount))
                .body("reference", is(REFERENCE))
                .body("description", is(DESCRIPTION))
                .body("payment_provider", is(PAYMENT_PROVIDER))
                .body("created_date", is(CREATED_DATE))
                .body("moto", is(true))
                .body("authorisation_mode", is(AuthorisationMode.MOTO_API.getName()))
                .body("_links.auth_url_post.type", is("application/json"))
                .body("_links.auth_url_post.method", is("POST"))
                .body("_links.auth_url_post.href", is("http://publicapi.url/v1/auth"))
                .body("_links.auth_url_post.params.one_time_token", is(CHARGE_TOKEN_ID));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, params);
    }

    @Test
    void createPaymentWithAuthorisationModeAgreement() {
        int amount = 1;

        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondCreated_whenCreateCharge_withAuthorisationMode_Agreement(CHARGE_TOKEN_ID, GATEWAY_ACCOUNT_ID, aCreateOrGetChargeResponseFromConnector()
                .withAmount(amount)
                .withChargeId(CHARGE_ID)
                .withState(CREATED)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withPaymentProvider(PAYMENT_PROVIDER)
                .withGatewayTransactionId(GATEWAY_TRANSACTION_ID)
                .withCreatedDate(CREATED_DATE)
                .withLanguage(SupportedLanguage.ENGLISH)
                .withDelayedCapture(false)
                .withRefundSummary(REFUND_SUMMARY)
                .withCardDetails(CARD_DETAILS)
                .withAuthorisationMode(AuthorisationMode.AGREEMENT)
                .withAgreementId(VALID_AGREEMENT_ID)
                .build());

        CreateChargeRequestParams params = aCreateChargeRequestParams()
                .withAmount(amount)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAuthorisationMode(AuthorisationMode.AGREEMENT)
                .withAgreementId(VALID_AGREEMENT_ID)
                .build();

        postPaymentResponse(paymentPayload(params))
                .statusCode(201)
                .contentType(JSON)
                .body("$", not(hasKey("return_url")))
                .body("$_links", not(hasKey("cancel")))
                .body("payment_id", is(CHARGE_ID))
                .body("amount", is(amount))
                .body("reference", is(REFERENCE))
                .body("description", is(DESCRIPTION))
                .body("payment_provider", is(PAYMENT_PROVIDER))
                .body("created_date", is(CREATED_DATE))
                .body("moto", is(false))
                .body("authorisation_mode", is(AuthorisationMode.AGREEMENT.getName()));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, params);
    }

    @Test
    void createPayment_withAllFieldsUpToMaxLengthBoundaries_shouldBeAccepted() {
        int amount = 10000000;
        String reference = RandomStringUtils.insecure().nextAlphanumeric(255);
        String description = RandomStringUtils.insecure().nextAlphanumeric(255);
        String email = RandomStringUtils.insecure().nextAlphanumeric(242) + "@example.org";
        String returnUrl = "https://govdemopay.gov.uk?data=" + RandomStringUtils.insecure().nextAlphanumeric(1969);

        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondCreated_whenCreateCharge(CHARGE_TOKEN_ID, GATEWAY_ACCOUNT_ID, aCreateOrGetChargeResponseFromConnector()
                .withAmount(amount)
                .withChargeId(CHARGE_ID)
                .withState(CREATED)
                .withReturnUrl(returnUrl)
                .withDescription(description)
                .withReference(reference)
                .withEmail(email)
                .withPaymentProvider(PAYMENT_PROVIDER)
                .withGatewayTransactionId(GATEWAY_TRANSACTION_ID)
                .withCreatedDate(CREATED_DATE)
                .withLanguage(SupportedLanguage.ENGLISH)
                .withDelayedCapture(false)
                .withRefundSummary(REFUND_SUMMARY)
                .withCardDetails(CARD_DETAILS)
                .build());

        String body = new JsonStringBuilder()
                .add("amount", amount)
                .add("reference", reference)
                .add("email", email)
                .add("card_brand", CARD_BRAND_LABEL)
                .add("description", description)
                .add("return_url", returnUrl)
                .build();

        postPaymentResponse(body)
                .statusCode(201)
                .contentType(JSON)
                .body("payment_id", is(CHARGE_ID))
                .body("amount", is(amount))
                .body("reference", is(reference))
                .body("email", is(email))
                .body("description", is(description))
                .body("return_url", is(returnUrl))
                .body("payment_provider", is(PAYMENT_PROVIDER))
                .body("card_brand", is(CARD_BRAND_LABEL))
                .body("created_date", is(CREATED_DATE));
    }

    @Test
    void createPayment_responseWith500_whenConnectorResponseIsAnUnrecognisedError() throws Exception {
        String gatewayAccountId = "1234567";
        String errorMessage = "something went wrong";

        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, gatewayAccountId);

        connectorMockClient.respondBadRequest_whenCreateCharge(gatewayAccountId, errorMessage);

        InputStream body = postPaymentResponse(SUCCESS_PAYLOAD)
                .statusCode(500)
                .contentType(JSON).extract()
                .body().asInputStream();

        JsonAssert.with(body)
                .assertThat("$.*", hasSize(2))
                .assertThat("$.code", is("P0198"))
                .assertThat("$.description", is("Downstream system error"));

        connectorMockClient.verifyCreateChargeConnectorRequest(gatewayAccountId, SUCCESS_PAYLOAD);
    }

    @Test
    void createPayment_responseWith500_whenTokenForGatewayAccountIsValidButConnectorResponseIsNotFound() {
        String notFoundGatewayAccountId = "9876545";
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, notFoundGatewayAccountId);

        connectorMockClient.respondNotFound_whenCreateCharge(notFoundGatewayAccountId);

        postPaymentResponse(SUCCESS_PAYLOAD)
                .statusCode(500)
                .contentType(JSON)
                .body("code", is("P0199"))
                .body("description", is("There is an error with this account. Contact support with your error code - https://www.payments.service.gov.uk/support/ ."));

        connectorMockClient.verifyCreateChargeConnectorRequest(notFoundGatewayAccountId, SUCCESS_PAYLOAD);
    }

    @Test
    void createPayment_responseWith422_whenMotoNotAllowed() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondMotoPaymentNotAllowed(GATEWAY_ACCOUNT_ID);

        String createMotoPaymentPayload = paymentPayload(aCreateChargeRequestParams()
                .withAmount(AMOUNT)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withMoto(true)
                .build());

        postPaymentResponse(createMotoPaymentPayload)
                .statusCode(422)
                .contentType(JSON)
                .body("code", is("P0196"))
                .body("description", is("MOTO payments are not enabled for this account. Please contact support if you would like to process MOTO payments - https://www.payments.service.gov.uk/support/ ."));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createMotoPaymentPayload);
    }

    @Test
    void createPayment_responseWith422_whenAuthApiNotAllowed() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondAuthorisationApiNotAllowed(GATEWAY_ACCOUNT_ID);

        String payload = paymentPayload(aCreateChargeRequestParams()
                .withAmount(AMOUNT)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withAuthorisationMode(AuthorisationMode.MOTO_API)
                .build());

        postPaymentResponse(payload)
                .statusCode(422)
                .contentType(JSON)
                .body("code", is("P0195"))
                .body("description", is("Using authorisation_mode of moto_api is not allowed for this account"));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, payload);
    }

    @Test
    void createPayment_responseWith422_whenZeroAmountNotAllowed() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondZeroAmountNotAllowed(GATEWAY_ACCOUNT_ID);

        postPaymentResponse(SUCCESS_PAYLOAD)
                .statusCode(422)
                .contentType(JSON)
                .body("code", is("P0102"))
                .body("field", is("amount"))
                .body("description", is("Invalid attribute value: amount. Must be greater than or equal to 1. Refer to https://docs.payments.service.gov.uk/making_payments/#amount"));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, SUCCESS_PAYLOAD);
    }

    @Test
    void createPayment_responseWith422_whenAmountBelowMinimumForAdyen() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondAmountBelowMinimumForAdyen(GATEWAY_ACCOUNT_ID);

        postPaymentResponse(SUCCESS_PAYLOAD)
                .statusCode(422)
                .contentType(JSON)
                .body("code", is("P0102"))
                .body("field", is("amount"))
                .body("description", is("Invalid attribute value: amount. Must be greater than or equal to 21. Refer to https://docs.payments.service.gov.uk/making_payments/#amount"));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, SUCCESS_PAYLOAD);
    }

    @Test
    void createPayment_responseWith422_whenAmountBelowMinimumForStripe() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondAmountBelowMinimumForStripe(GATEWAY_ACCOUNT_ID);

        postPaymentResponse(SUCCESS_PAYLOAD)
                .statusCode(422)
                .contentType(JSON)
                .body("code", is("P0102"))
                .body("field", is("amount"))
                .body("description", is("Invalid attribute value: amount. Must be greater than or equal to 30. Refer to https://docs.payments.service.gov.uk/making_payments/#amount"));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, SUCCESS_PAYLOAD);
    }

    @Test
    void createPayment_Returns401_WhenUnauthorised() {
        publicAuthMockClient.respondUnauthorised();
        postPaymentResponse(SUCCESS_PAYLOAD).statusCode(401);
    }

    @Test
    void createPayment_Returns403_WhenGatewayAccountCredentialsNotFullyConfigured() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);
        connectorMockClient.respondGatewayAccountCredentialNotConfigured(GATEWAY_ACCOUNT_ID);
        postPaymentResponse(SUCCESS_PAYLOAD)
                .statusCode(403)
                .contentType(JSON)
                .body("code", is("P0940"))
                .body("description", is("Account is not fully configured. Please refer to documentation to setup your account or contact support with your error code - https://www.payments.service.gov.uk/support/ ."));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, SUCCESS_PAYLOAD);
    }

    @Test
    void createPayment_Returns400_WhenCardNumberIsEnteredInPaymentLinkReference() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);
        connectorMockClient.respondCardNumberInReferenceError(GATEWAY_ACCOUNT_ID);

        String payload = paymentPayload(aCreateChargeRequestParams()
                .withAmount(AMOUNT)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withSource(CARD_PAYMENT_LINK)
                .build());
        postPaymentResponse(payload)
                .statusCode(400)
                .contentType(JSON)
                .body("code", is("P0105"))
                .body("description", is("Card number entered in a payment link reference"));

        String connectorPayload = new JsonStringBuilder().add("amount", AMOUNT).add("reference", REFERENCE).add("description", DESCRIPTION)
                .add("return_url", RETURN_URL).add("source", CARD_PAYMENT_LINK).build();
        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, connectorPayload);
    }

    @Test
    void createPayment_Returns_WhenPublicAuthInaccessible() {
        publicAuthMockClient.respondWithError();
        postPaymentResponse(SUCCESS_PAYLOAD).statusCode(503);
    }

    @Test
    void createCardPaymentWithSource() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .withSource(CARD_PAYMENT_LINK)
                .build();
        connectorMockClient.respondCreated_whenCreateCharge(GATEWAY_ACCOUNT_ID, createChargeRequestParams);

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(201)
                .contentType(JSON);

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, createChargeRequestParams);
    }

    @Test
    void shouldReturnBadRequestWhenPaymentReferenceContainsIllegalCharacters() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(ILLEGAL_REFERENCE)
                .withReturnUrl(RETURN_URL)
                .build();

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(HttpStatus.SC_BAD_REQUEST)
                .contentType(JSON)
                .body("field", is("reference"))
                .body("code", is("P0102"))
                .body("description", is("Invalid attribute value: reference. Must be a valid string format"));
    }

    @Test
    void shouldReturnBadRequestWhenPaymentDescriptionContainsIllegalCharacters() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID, CARD);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(ILLEGAL_DESCRIPTION)
                .withReference(REFERENCE)
                .withReturnUrl(RETURN_URL)
                .build();

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(HttpStatus.SC_BAD_REQUEST)
                .contentType(JSON)
                .body("field", is("description"))
                .body("code", is("P0102"))
                .body("description", is("Invalid attribute value: description. Must be a valid string format"));
    }

    @Test
    void createPaymentWithAgreementPaymentTypeInstalment() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondCreated_whenCreateCharge_withAgreementPaymentType_Instalment(CHARGE_TOKEN_ID, GATEWAY_ACCOUNT_ID, aCreateOrGetChargeResponseFromConnector()
                .withAmount(100)
                .withChargeId(CHARGE_ID)
                .withState(CREATED)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withPaymentProvider(PAYMENT_PROVIDER)
                .withGatewayTransactionId(GATEWAY_TRANSACTION_ID)
                .withCreatedDate(CREATED_DATE)
                .withLanguage(SupportedLanguage.ENGLISH)
                .withDelayedCapture(false)
                .withCardDetails(CARD_DETAILS)
                .withAuthorisationMode(AuthorisationMode.AGREEMENT)
                .withAgreementPaymentType(AgreementPaymentType.INSTALMENT)
                .build());

        CreateChargeRequestParams params = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAuthorisationMode(AuthorisationMode.AGREEMENT)
                .withAgreementPaymentType(AgreementPaymentType.INSTALMENT)
                .withAgreementId(VALID_AGREEMENT_ID)
                .build();

        postPaymentResponse(paymentPayload(params))
                .statusCode(201)
                .contentType(JSON)
                .body("$", not(hasKey("return_url")))
                .body("$_links", not(hasKey("cancel")))
                .body("payment_id", is(CHARGE_ID))
                .body("amount", is(100))
                .body("reference", is(REFERENCE))
                .body("description", is(DESCRIPTION))
                .body("payment_provider", is(PAYMENT_PROVIDER))
                .body("created_date", is(CREATED_DATE))
                .body("authorisation_mode", is(AuthorisationMode.AGREEMENT.getName()))
                .body("agreement_payment_type", is(AgreementPaymentType.INSTALMENT.getName()));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, params);
    }

    @Test
    void createPaymentWithAgreementWithoutAgreementPaymentType() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondCreated_whenCreateCharge_withAuthorisationMode_Agreement(CHARGE_TOKEN_ID, GATEWAY_ACCOUNT_ID, aCreateOrGetChargeResponseFromConnector()
                .withAmount(100)
                .withChargeId(CHARGE_ID)
                .withState(CREATED)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withPaymentProvider(PAYMENT_PROVIDER)
                .withGatewayTransactionId(GATEWAY_TRANSACTION_ID)
                .withCreatedDate(CREATED_DATE)
                .withLanguage(SupportedLanguage.ENGLISH)
                .withDelayedCapture(false)
                .withCardDetails(CARD_DETAILS)
                .withAuthorisationMode(AuthorisationMode.AGREEMENT)
                .build());

        CreateChargeRequestParams params = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAuthorisationMode(AuthorisationMode.AGREEMENT)
                .withAgreementId(VALID_AGREEMENT_ID)
                .build();

        postPaymentResponse(paymentPayload(params))
                .statusCode(201)
                .contentType(JSON)
                .body("$", not(hasKey("return_url")))
                .body("$_links", not(hasKey("cancel")))
                .body("payment_id", is(CHARGE_ID))
                .body("amount", is(100))
                .body("reference", is(REFERENCE))
                .body("description", is(DESCRIPTION))
                .body("payment_provider", is(PAYMENT_PROVIDER))
                .body("created_date", is(CREATED_DATE))
                .body("authorisation_mode", is(AuthorisationMode.AGREEMENT.getName()))
                .body("agreement_payment_type", is(nullValue()));

        connectorMockClient.verifyCreateChargeConnectorRequest(GATEWAY_ACCOUNT_ID, params);
    }

    @Test
    void createPaymentWithAgreementPaymentType_responseWith400_whenNoAuthorisationModeOrSetUpAgreement() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondUnexpectedAttributesForAgreementPaymentType_whenCreatedCharge(GATEWAY_ACCOUNT_ID, VALID_AGREEMENT_ID, "error message from connector");

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAgreementPaymentType(AgreementPaymentType.INSTALMENT)
                .build();

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(400)
                .contentType(JSON)
                .body("field", is(nullValue()))
                .body("code", is("P0104"))
                .body("description", is("Unexpected attribute: agreement_payment_type"));
    }

    @Test
    void createPaymentWithAgreementPaymentType_responseWith400_whenAuthorisationModeNotAgreement() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAuthorisationMode(AuthorisationMode.WEB)
                .withAgreementPaymentType(AgreementPaymentType.INSTALMENT)
                .build();
        connectorMockClient.respondUnexpectedAttributesForAgreementPaymentType_whenCreatedCharge(GATEWAY_ACCOUNT_ID, VALID_AGREEMENT_ID, "error message from connector");

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(400)
                .contentType(JSON)
                .body("field", is(nullValue()))
                .body("code", is("P0104"))
                .body("description", is("Unexpected attribute: agreement_payment_type"));
    }

    @Test
    void createPaymentWithUnexpectedAgreementPaymentTypeAndPaymentId_responseWith400() {
        publicAuthMockClient.mapBearerTokenToAccountId(API_KEY, GATEWAY_ACCOUNT_ID);

        connectorMockClient.respondUnexpectedAttributesForAgreementPaymentType_whenCreatedCharge(GATEWAY_ACCOUNT_ID, VALID_AGREEMENT_ID, "Unexpected attribute: agreement_id");

        CreateChargeRequestParams createChargeRequestParams = aCreateChargeRequestParams()
                .withAmount(100)
                .withDescription(DESCRIPTION)
                .withReference(REFERENCE)
                .withAgreementPaymentType(AgreementPaymentType.INSTALMENT)
                .withAgreementId(VALID_AGREEMENT_ID)
                .build();

        postPaymentResponse(paymentPayload(createChargeRequestParams))
                .statusCode(400)
                .contentType(JSON)
                .body("field", is(nullValue()))
                .body("code", is("P0104"))
                .body("description", is("Unexpected attribute: agreement_id"));
    }

    public static String paymentPayload(CreateChargeRequestParams params) {
        JsonStringBuilder payload = new JsonStringBuilder()
                .add("amount", params.getAmount())
                .add("reference", params.getReference())
                .add("description", params.getDescription())
                .add("return_url", params.getReturnUrl());

        if (!params.getMetadata().isEmpty()) {
            payload.add("metadata", params.getMetadata());
        }

        if (params.getEmail() != null) {
            payload.add("email", params.getEmail());
        }

        if (params.isMoto() != null) {
            payload.add("moto", params.isMoto());
        }

        params.getCardholderName()
                .ifPresent(
                        cardholderName -> payload.addToNestedMap("cardholder_name", cardholderName, "prefilled_cardholder_details"));

        params.getAddressLine1()
                .ifPresent(
                        addressLine1 -> payload.addToNestedMap("line1", addressLine1, "prefilled_cardholder_details", "billing_address"));

        params.getAddressLine2()
                .ifPresent(
                        addressLine2 -> payload.addToNestedMap("line2", addressLine2, "prefilled_cardholder_details", "billing_address"));

        params.getAddressPostcode()
                .ifPresent(
                        addressPostcode -> payload.addToNestedMap("postcode", addressPostcode, "prefilled_cardholder_details", "billing_address"));

        params.getAddressCity()
                .ifPresent(
                        addressCity -> payload.addToNestedMap("city", addressCity, "prefilled_cardholder_details", "billing_address"));

        params.getAddressCountry()
                .ifPresent(
                        addressCountry -> payload.addToNestedMap("country", addressCountry, "prefilled_cardholder_details", "billing_address"));

        params.getSource().ifPresent(source -> payload.addToNestedMap("source", source, "internal"));
        params.getSetUpAgreement().ifPresent(setUpAgreement -> payload.add("set_up_agreement", setUpAgreement));
        params.getAgreementId().ifPresent(agreementId -> payload.add("agreement_id", agreementId));
        params.getAuthorisationMode().ifPresent(authorisationMode -> payload.add("authorisation_mode", authorisationMode));
        params.getAgreementPaymentType().ifPresent(agreementPaymentType -> payload.add("agreement_payment_type", agreementPaymentType));

        return payload.build();
    }

    @Override
    protected ValidatableResponse postPaymentResponse(String payload) {
        return given().port(app.getLocalPort())
                .body(payload)
                .accept(JSON)
                .contentType(JSON)
                .header(AUTHORIZATION, "Bearer " + API_KEY)
                .post(PAYMENTS_PATH)
                .then();
    }
}
