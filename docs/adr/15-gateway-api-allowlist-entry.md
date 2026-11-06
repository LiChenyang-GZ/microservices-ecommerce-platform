# ADR-15 · The single entry is Gateway API (Envoy Gateway) with an allowlist

**Status:** Accepted (replaces "Ingress" in the first version of ADR-13)

## Context
The first plan used an Ingress. By the time the Kubernetes step was built, ingress-nginx had had no releases or security fixes since March 2026, and kind's documentation pointed to Gateway API instead. Separately, the first version of the routes forwarded whole prefixes (`/api/store/*`, `/api/delivery/*`) and added a 404 rule for each internal path someone remembered. A review found two it missed: delivery's token-free `cancel-by-order` (anyone could cancel a delivery and so trigger a refund) and store's `payment-success` (an order marked PAID without bank).

## Decision
- The entry is Envoy Gateway, configured with Gateway API resources (`Gateway`, `HTTPRoute`).
- Routes are an **allowlist**: only the method + path pairs the frontend calls are forwarded, one by one. Everything else under `/api`, plus `/actuator` and `/internal`, gets 404 from the entry itself.
- bank and tracking get no routes; the frontend does not call them.
- compose has the same list in `frontend/nginx.conf`, so `localhost:3000` behaves like `localhost`; `check-entry.ps1` sends 58 requests to both and reports any difference.

## Consequences
- A new backend endpoint is unreachable from outside until someone adds it to both lists.
- Two lists must be kept in step (checked by the script).
- Envoy Gateway is one more controller to install (`up.ps1` does it).
- An occasional 503 through the entry (finding I2) is not yet explained.

## In the code
- [gateway.yaml](../../k8s/gateway/gateway.yaml), [routes.yaml](../../k8s/gateway/routes.yaml), [nginx.conf](../../frontend/nginx.conf), [check-entry.ps1](../../k8s/scripts/check-entry.ps1)
