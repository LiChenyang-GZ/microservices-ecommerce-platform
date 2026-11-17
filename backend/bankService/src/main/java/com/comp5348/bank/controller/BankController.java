package com.comp5348.bank.controller;

import com.comp5348.bank.dto.*;
import com.comp5348.bank.service.BankService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * bank's REST side: only store calls it, with X-Service-Token (ServiceTokenFilter).
 * Money moves only over gRPC (ADR-10, BankGrpcService); the old POST /transfer and /refund and the
 * logged-in-user GET /account/me had no callers and were removed (Christy's decision 3, 2026-11-17).
 */
@RestController
@RequestMapping("/api/bank")
public class BankController {

    @Autowired
    private BankService bankService;

    /**
     * Query balance interface
     */
    @GetMapping("/account/{accountNumber}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountNumber) {
        BalanceResponse response = bankService.getBalance(accountNumber);

        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Create bank account
     */
    @PostMapping("/account")
    public ResponseEntity<BankAccountCreateResponse> createAccount(@RequestBody BankAccountCreateRequest request) {
        BankAccountCreateResponse resp = bankService.createAccount(request);
        if (resp.isSuccess()) return ResponseEntity.ok(resp);
        return ResponseEntity.badRequest().body(resp);
    }

    /**
     * Get account by owner email (for inter-service communication)
     */
    @GetMapping("/account/by-owner/{ownerEmail}")
    public ResponseEntity<BankAccountCreateResponse> getAccountByOwner(@PathVariable String ownerEmail) {
        BankAccountCreateResponse resp = bankService.getOrCreateAccountByEmail(ownerEmail);
        if (resp.isSuccess()) {
            return ResponseEntity.ok(resp);
        }
        return ResponseEntity.notFound().build();
    }
}
