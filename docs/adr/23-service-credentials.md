# ADR-23 · Calls between services carry a shared secret, one per called service

**English** | [中文](23-service-credentials.zh-CN.md)

**Status:** Accepted, amended by 25 (`verify-code` is for store only) and 26 (email has no public endpoint)

## Context
Until this decision nothing checked who was calling inside the network. bank's gRPC `Transfer` / `Refund` had no interceptor; bank's REST let a request without a token through, and `POST /api/bank/transfer` could move money from `SYSTEM` to any account; delivery's `/internal/deliveries/cancel-by-order` checked nothing; email's notification endpoints could send mail as the shop to any address. The entry's allowlist (ADR-15) kept all of this away from the outside, but any pod in the cluster, any container in the compose network or any process on the host could call them directly.

Three ways for a service to prove who it is were considered:

| Option | Cost |
|---|---|
| **A shared secret per called service** (chosen) | A leaked secret opens that service; rotating it means changing both sides. Half a day, works the same on compose and kind |
| mTLS with a private CA | Certificates to generate, mount, renew; TLS on every probe and client. About 2–3 days locally, mostly infrastructure |
| Short-lived JWTs signed by the caller | No shared secret and tokens expire, but one more key-management system |

## Decision
- Each called service has **its own** secret: `BANK_SERVICE_TOKEN`, `DELIVERY_SERVICE_TOKEN`, `EMAIL_SERVICE_TOKEN`. The caller sends it in the HTTP header `X-Service-Token` or the gRPC metadata key `x-service-token` (gRPC keys must be lower case).

  | Called service | Checked on | Missing or wrong |
  |---|---|---|
  | bank | gRPC (a `ServerInterceptor` wraps the whole service) and every `/api/bank/*` REST path; `/actuator` stays open for probes | `UNAUTHENTICATED` / 401, nothing runs |
  | delivery | `/internal/*` only; `/api/*` keeps the user-token filter | 401 |
  | email | every `/api/*` path **except** `send-verification` and `verify-code` (the registration page), matched exactly. Since ADR-25 only `send-verification`; since ADR-26 none (store asks email for a code) | 401 |

- email's check is an **allowlist**: a path is public only if it is on the list, so an endpoint added later is closed until someone opens it. The old filter matched public paths with `startsWith`, so `/api/email/send-verification-anything` also got through.
- The secret is compared with `MessageDigest.isEqual` (constant time). A rejected request is logged as "no / wrong service token" with the method and path, never the secret.
- Every service, the caller store included, **refuses to start** when one of its secrets is blank, and the error names the key. A service that starts without it either rejects every call or, worse, skips the check.
- store sends each callee only that callee's secret: one `RestTemplate` or gRPC channel per callee, with the header attached there. When a callee refuses the secret, store keeps the answer it already gave and adds an alert line, because a wrong secret does not heal itself the way a timeout does:
  - bank gRPC `UNAUTHENTICATED` → still UNKNOWN (ADR-22), same key retried, plus `ALERT bank_auth_failed key=…`. Never REJECTED: the secret is our mistake, not the buyer's.
  - bank REST 401 (open account, account by email) → still `null`, plus `ALERT bank_auth_failed op=…` without the email.
  - delivery 401 → the cancel is refused as "try again later", not "too late", plus `ALERT delivery_auth_failed orderId=…`.
  - email 401 → the password reset fails and the password is not changed, plus `ALERT email_auth_failed`.
- Endpoints nobody called are **removed**, not protected: bank `POST /api/bank/transfer`, `/refund`, `GET /api/bank/account/me`; email's four notification endpoints and `check-verified` (notification emails go over RabbitMQ `email_queue`).
- Network: compose no longer publishes bank's gRPC port 9090. On Kubernetes a NetworkPolicy lets only store pods reach bank:9090; 8084 stays open to all pods (kubelet probes use it, and every REST path needs the secret anyway).
- Each service has its own copy of `ServiceToken` and `ServiceTokenFilter`. `contracts` is the event and proto module and should not carry servlet dependencies; the services are built and deployed separately.

## Consequences
- **A leaked secret opens that one service** until it is changed. store holds all three, so a compromised store opens all three; that is the caller's role, and mTLS would not change it.
- **No rotation without a gap.** Each callee accepts one secret. Changing it means updating the value (`backend/.env` or the Secret) and restarting both sides; in between, payments come back UNKNOWN and are retried by the outbox, cancels are refused and password resets fail, all with alerts. Accepting a current and a previous secret would close the gap; not done.
- **The startup check catches a blank secret, not a wrong one.** Two different non-empty values start fine and show up as alerts at the first call.
- **Secrets travel in plain text** inside the network (`usePlaintext()`, plain HTTP). Anyone who can read the traffic can read the secret. This is the main thing mTLS or a service mesh would add.
- **`email_queue` is not covered.** Anything with the RabbitMQ account can put a notification on the queue; that path is protected by the RabbitMQ credentials only.
- The NetworkPolicy is enforced only if the cluster's network plugin implements NetworkPolicy. kind's default kindnet does since kind v0.24 (checked on v0.33: delivery → bank:9090 is blocked); on a plugin without it, only the secret protects bank.
- Three copies of the filter must be changed together.
- This is service identity only. Which **user** may cancel or refund which order, and who is an admin, are separate decisions (the next two auth PRs).

## In the code
- bank [ServiceToken.java](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceToken.java), [ServiceTokenFilter.java](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceTokenFilter.java), [FilterConfig.java](../../backend/bankService/src/main/java/com/comp5348/bank/config/FilterConfig.java), [ServiceTokenServerInterceptor.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/ServiceTokenServerInterceptor.java), [GrpcServerLifecycle.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/GrpcServerLifecycle.java)
- delivery [ServiceToken.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/ServiceToken.java), [ServiceTokenFilter.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/ServiceTokenFilter.java), [FilterConfig.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/FilterConfig.java)
- email [ServiceToken.java](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceToken.java), [ServiceTokenFilter.java](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java)
- store [ServiceTokens.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/ServiceTokens.java), [BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java), [DeliveryAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/DeliveryAdapter.java), [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)
- Deployment: [compose.yml](../../backend/compose.yml), [.env.example](../../backend/.env.example), [apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1), [check-config-keys.ps1](../../k8s/scripts/check-config-keys.ps1), [bank.yaml](../../k8s/apps/bank.yaml) (NetworkPolicy at the end)
- Tests: bank `BankGrpcAuthIntegrationTest`, `BankRestAuthIntegrationTest`, `OldBankRestEndpointsRemovedTest`; delivery `CancelByOrderAuthIntegrationTest`; email `EmailServiceTokenIntegrationTest`, `OldEmailEndpointsRemovedTest`; store `BankServiceTokenIntegrationTest`, `DeliveryServiceTokenIntegrationTest`, `EmailServiceTokenIntegrationTest`, `EmailCallTimeoutIntegrationTest`; one `*StartupWithoutServiceTokenTest` per service
- [docs/learning/10-service-credentials.md](../learning/10-service-credentials.md)
