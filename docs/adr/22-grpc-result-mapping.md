# ADR-22 · How a gRPC reply from bank maps to succeeded, rejected or unknown

**English** | [中文](22-grpc-result-mapping.zh-CN.md)

**Status:** Accepted

## Context
ADR-05 needs every call to bank to end in exactly one of three results: SUCCEEDED, REJECTED (bank definitely said no) or UNKNOWN (money may or may not have moved). Moving the call to gRPC (ADR-10) raised cases the plan did not cover: a reply with status OK but no outcome set; a bad amount string; and bank restarting, where gRPC's reconnect back-off could make all 5 fast retries fail at once.

## Decision
- bank answers its own business result as status **OK + outcome** `SUCCEEDED` / `REJECTED`. An unparsable amount is `REJECTED`, not `INVALID_ARGUMENT`: as an error status it would become UNKNOWN in store and be retried every 10 minutes forever.
- In store, **any gRPC error status is UNKNOWN** (`DEADLINE_EXCEEDED` and `UNAVAILABLE` may come after bank already moved the money).
- Status OK with the outcome missing, or a value store does not know, is **UNKNOWN, never REJECTED**: a wrong REJECTED would mark the payment FAILED and let Pay Now charge again with a new key.
- Each call has a deadline and uses `waitForReady`: while bank is restarting, the call waits for the connection until its deadline instead of failing at once.
- grpc-java (`grpc-netty-shaded`) with a small hand-written Spring configuration, not a Spring gRPC starter.

## Consequences
- The only way to REJECTED is bank explicitly saying so; every doubt leans to UNKNOWN, which is safe for money and costs a slower, alerted path (ADR-05).
- A bank that forgets to set the outcome shows up as alerts, not as wrong FAILED payments.

## In the code
- store [BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java), [BankCallResult.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankCallResult.java); bank [BankGrpcService.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java)
- Tests: `BankGrpcMappingIntegrationTest`, `BankGrpcIntegrationTest`
- [docs/learning/05-grpc-bank-call.md](../learning/05-grpc-bank-call.md)
