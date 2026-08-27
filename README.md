# pay-publicapi

GOV.UK Pay Public API service in Java (Dropwizard)

## General configuration

Configuration of the application is performed via environment variables, some of which are mandatory.

| Variable                    | Required? | Default        | Description                                                                                                |
|-----------------------------|-----------|----------------|------------------------------------------------------------------------------------------------------------|
| `ADMIN_PORT`                | No        | 8081           | The port number to listen for Dropwizard admin requests on.                                                |
| `ALLOW_HTTP_FOR_RETURN_URL` | No        | false          | Whether to allow service return URLs to be non-HTTPS                                                       |
| `BIND_HOST`                 | No        | 127.0.0.1      | The IP address for the application to bind to.                                                             |
| `CONNECTOR_URL`             | Yes       | -              | The URL to the [connector](https://github.com/govuk-pay/pay-connector) service                              |
| `DISABLE_INTERNAL_HTTPS`    | No        | false          | Disable secure connection for calls to internal APIs                                                       |
| `PORT`                      | No        | 8080           | The port number to listen for requests on.                                                                 |
| `PUBLICAPI_BASE`            | Yes       | -              | The base URL clients can use to reach the API. e.g. http://api.example.org:1234/                           |
| `PUBLIC_AUTH_URL`           | Yes       | -              | The URL to the [publicauth](https://github.com/govuk-pay/pay-publicauth) service                            |
| `REDIS_URL`                 | No        | localhost:6379 | The location of the Redis endpoint to store rate-limiter information in                                    |
| `REDIS_SSL`                 | No        | false          | Whether to establish TLS encrypted connections to the Redis instance                                       |
| `TOKEN_API_HMAC_SECRET`     | Yes       | -              | HMAC secret to be used to validate that the given token is genuine (API Key = Token + HMAC (Token, Secret) |

## Rate limiting

The application will rate-limit incoming API requests, recording the current
rate limit state in Redis (see `REDIS_URL` above). The rate-limiting behaviour
can be tuned via the following environment variables which all have default
values:

| Variable                              | Default | Description                                                                                                                                                   |
|---------------------------------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `RATE_LIMITER_VALUE`                  | 75      | Number of non-`POST` requests allowed per `RATE_LIMITER_PER_MILLIS` milliseconds                                                                              |
| `RATE_LIMITER_VALUE_POST`             | 15      | Number of `POST` requests allowed per `RATE_LIMITER_PER_MILLIS` milliseconds                                                                                  |
| `RATE_LIMITER_ELEVATED_ACCOUNTS`      | -       | Comma-separated list of accounts to which `..._ELEVATED_...` limits apply (example: `1,2,3`)                                                                  |
| `RATE_LIMITER_ELEVATED_VALUE_GET`     | 100     | Number of non-`POST` requests allowed per `RATE_LIMITER_PER_MILLIS` milliseconds (for `RATE_LIMITER_ELEVATED_ACCOUNTS`)                                       |
| `RATE_LIMITER_ELEVATED_VALUE_POST`    | 40      | Number of `POST` requests allowed per `RATE_LIMITER_PER_MILLIS` milliseconds (for `RATE_LIMITER_ELEVATED_ACCOUNTS`)                                           |
| `RATE_LIMITER_VALUE_PER_NODE`         | 25      | Number of non-`POST` requests allowed per `RATE_LIMITER_PER_MILLIS` milliseconds for a given client                                                           |
| `RATE_LIMITER_VALUE_PER_NODE_POST`    | 5       | Number of `POST` requests allowed per `RATE_LIMITER_PER_MILLIS` milliseconds for a given client                                                               |
| `RATE_LIMITER_PER_MILLIS`             | 1000    | Rate limiter time window                                                                                                                                      |
| `RATE_LIMITER_LOW_TRAFFIC_ACCOUNTS`   | -       | Comma-separated list of accounts to which `..._LOW_TRAFFIC_...` limits apply (example: `5,6,7`)                                                               |
| `RATE_LIMITER_LOW_TRAFFIC_VALUE_GET`  | 4500    | Number of non-`POST` requests allowed per `RATE_LIMITER_LOW_TRAFFIC_PER_MILLIS` in milliseconds for a given account (for `RATE_LIMITER_LOW_TRAFFIC_ACCOUNTS`) |
| `RATE_LIMITER_LOW_TRAFFIC_VALUE_POST` | 1       | Number of `POST` requests allowed per `RATE_LIMITER_LOW_TRAFFIC_PER_MILLIS` in milliseconds (for `RATE_LIMITER_LOW_TRAFFIC_ACCOUNTS`)                         |
| `RATE_LIMITER_LOW_TRAFFIC_PER_MILLIS` | 60000   | rate limit internal per `RATE_LIMITER_LOW_TRAFFIC_PER_MILLIS` (in milliseconds) for `RATE_LIMITER_LOW_TRAFFIC_ACCOUNTS`                                       |

## API specification

Read our  [developer documentation](https://docs.payments.service.gov.uk/#gov-uk-pay-documentation) for guidance on
using the API.

For more detailed information you can use
our [OpenAPI specifiation](https://github.com/govuk-pay/pay-publicapi/blob/master/openapi/publicapi_spec.json).

## Dependencies

[WireMock](https://wiremock.org/) is used for mocking dependent services

## Licence

[MIT License](LICENSE)

## Vulnerability Disclosure

GOV.UK Pay aims to stay secure for everyone. If you are a security researcher and have discovered a security
vulnerability in this code, we appreciate your help in disclosing it to us in a responsible manner. Please refer to
our [vulnerability disclosure policy](https://www.gov.uk/help/report-vulnerability) and
our [security.txt](https://vdp.cabinetoffice.gov.uk/.well-known/security.txt) file for details.
