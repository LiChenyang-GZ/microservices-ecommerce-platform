# ADR-14 · What this design leaves as it was

**English** | [中文](14-out-of-scope.zh-CN.md)

**Status:** Accepted

## Context
The redesign is about events and money. Some weak spots of the course version are unrelated to that and were left on purpose.

## Decision
- **Tokens are still checked by store.** bank, delivery and tracking call store to validate a user's token.
- **Emails are still sent through RabbitMQ**, outside the business transaction.
- **Orders and stock stay in one service** (store).

## Consequences
- If store is down, the other services cannot check tokens either.
- An email can be lost in rare cases (for example RabbitMQ down at the moment of sending); store logs the failure and carries on. That is acceptable because an email affects neither money nor goods.
- There is still no service-to-service authentication; see the limitations in the README.

## In the code
- delivery [TokenAuthenticationFilter.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/TokenAuthenticationFilter.java), bank [TokenAuthenticationFilter.java](../../backend/bankService/src/main/java/com/comp5348/bank/config/TokenAuthenticationFilter.java), tracking [auth.go](../../backend/trackingService/internal/auth/auth.go)
- store [EmailAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/EmailAdapter.java)
