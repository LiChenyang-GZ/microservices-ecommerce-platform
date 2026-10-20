# Scenario scripts

**English** | [中文](README.zh-CN.md)

Try the running system the way a user would, and check each service's own database afterwards. Each script registers a fresh user, so it can be run again.

Start the stack first, then run from the repository root (PowerShell 7):

```
docker compose -f backend/compose.yml up -d --wait
pwsh backend/docker/scenarios/<script>.ps1
```

| Script | What a user does | What must be true | Time |
|---|---|---|---|
| `01-paynow-twice.ps1` | Payment fails (not enough money), tops up, then clicks Pay Now in two tabs at once | Charged once; two PAY tasks in total (the rejected one + one Pay Now) | ~20 s |
| `02-cancel-after-paid.ps1` | Pays, then cancels right away, before the delivery is created | Order CANCELLED, no delivery created, one refund, balance and stock back to the start | ~20 s |
| `02-cancel-after-paid.ps1 -AfterDelivery` | Pays, waits for the delivery to be created, cancels before pickup | Same, and the delivery is CANCELLED. Pickup happens ~10 s after the delivery is created; if you miss it, run again | ~25 s |
| `03-bank-down-during-payment.ps1` | Orders while the bank is down, clicks Pay Now while the result is unknown, bank comes back | `PAY_UNKNOWN` + `ALERT payment_unknown` log; Pay Now does not start a new attempt; after the bank is back the same key is retried and the charge happens once. The script always starts the bank again | ~11 min |
| `04-kafka-down.ps1` | Orders while Kafka is down, then Kafka comes back | The order still reaches PAID and store / delivery stay healthy; OrderPaid waits in store's `event_outbox`; once Kafka is back the delivery is created. The script always starts Kafka again | ~1 min |
| `05-rabbitmq-down-at-delivery.ps1` | Orders while RabbitMQ is down (the delivery is created at that moment), then RabbitMQ comes back | The order is not cancelled or refunded; once RabbitMQ is back the delivery moves past CREATED. Wave 2 integration finding I1: before the fix it stays in CREATED for good. The script always starts RabbitMQ again | ~1.5 min |
| `06-sequence-a-order-to-received.ps1` | Design sequence A: orders and waits until the parcel is received | Order DELIVERED; charged once; one delivery row, RECEIVED; tracking timeline is OrderPlaced → OrderPaid → DeliveryCreated → PickedUp → Delivering → Received | ~1 min |
| `07-sequence-b-lost-refund.ps1` | Design sequence B: the parcel is lost (the script restarts delivery with a 100% loss rate, then puts it back) | Order and payment REFUNDED; refunded once, balance back to the start; stock **not** returned; one Lost and one OrderRefunded in the timeline | ~1 min |
| `08-sequence-c-cancel-before-delivery-reads.ps1` | Design sequence C: cancels right after paying, before delivery reads OrderPaid (Kafka is stopped meanwhile, so OrderPaid arrives late) | Cancel marker in delivery; order CANCELLED, refunded once, stock returned; the late OrderPaid creates no delivery; delivery says Cancelled again and store skips it | ~1 min |

`smoke-order.ps1` (one level up) is the happy path: one order goes from PAID to IN_TRANSIT.

Notes:
- `iPhone 13` has little stock in the seed data, and script 01 buys two. After a few runs, orders fail with "Insufficient stock".
- Script 03 waits for the slow retry (`payment.bank.slow-retry-minutes`, 10 by default).
