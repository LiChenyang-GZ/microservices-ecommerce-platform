# ADR-10 · gRPC between services for money, REST for the browser

**English** | [中文](10-grpc-inside-rest-outside.zh-CN.md)

**Status:** Accepted

## Context
The store → bank call is the one call where a mistake costs money. Over HTTP + JSON its request and response were loosely typed, and a timeout and a business failure were hard to tell apart.

## Decision
- store charges and refunds through gRPC: `BankService.Transfer` and `BankService.Refund`, defined in `bank.proto` in the shared contracts module. The response carries an explicit outcome.
- The browser keeps calling REST endpoints through the single entry (ADR-13, ADR-15).
- Calls that do not move money stay on REST: store opens a customer's bank account and looks it up over HTTP, and calls email over HTTP for password resets.

## Consequences
- Types are checked at compile time and the client is generated. Each call has a deadline; no answer within it is "unknown" (ADR-05).
- One `.proto` file to maintain; debugging needs a gRPC tool such as grpcurl (bank publishes port 9090 in compose for that).

## In the code
- [bank.proto](../../backend/contracts/src/main/proto/bank/v1/bank.proto)
- bank [BankGrpcService.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java); store [BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java)
- [docs/learning/05-grpc-bank-call.md](../learning/05-grpc-bank-call.md)
