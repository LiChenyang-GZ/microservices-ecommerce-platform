# ADR-09 · Cancelling an order: a synchronous call plus a cancel marker

**Status:** Accepted

## Context
The customer wants to know right away whether a cancel worked, so the answer cannot wait for events. But a cancel can reach delivery **before** delivery has read the `OrderPaid` that would create the delivery row (sequence C). Without care, the late `OrderPaid` would create a delivery for an order that is already cancelled.

## Decision
- If money has moved for the order, store locks the order row and calls delivery synchronously: `POST /internal/deliveries/cancel-by-order/{orderId}` (5 s timeout). An unpaid order cannot have a delivery yet, so store makes no call; delivery still leaves a cancel marker when it reads that order's `OrderCancelled`, in case a charge lands later.
- delivery answers by case, and the call is idempotent:
  - no delivery row yet → insert a **cancel marker** (a row for that order with status CANCELLED) → `CANCELLED_BEFORE_CREATED`
  - row not yet picked up → CANCELLED
  - already cancelled → `ALREADY_CANCELLED`
  - already picked up → `TOO_LATE` (store refuses the cancel)
- When the late `OrderPaid` arrives, the unique `order_id` (ADR-07) makes it find the marker and create nothing. delivery publishes `Cancelled` again; store sees the order is already CANCELLED and skips it.
- If delivery agrees, store cancels the order, writes the REFUND task, returns the stock and records `OrderCancelled`. A charge result arriving at the same moment waits for the order lock, then sees CANCELLED and writes the refund itself.

## Consequences
- The race is decided by one unique index and a row lock, so it always has a winner.
- Cancelling depends on delivery being up: if it does not answer, store tells the customer to try again later.
- `/internal/...` has no service-to-service authentication yet; only the entry's allowlist keeps it private.

## In the code
- delivery [DeliveryService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java) (`cancelByOrderId`, `CancelOutcome`), [InternalDeliveryController.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/controller/InternalDeliveryController.java)
- store [DeliveryAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/DeliveryAdapter.java), [OrderService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java)
- Scenario `08-sequence-c-cancel-before-delivery-reads.ps1`
