package com.comp5348.bank.controller;

import com.comp5348.bank.dto.*;
import com.comp5348.bank.service.BankService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bank")
@CrossOrigin(origins = "*")
public class BankController {

    @Autowired
    private BankService bankService;
    
    /**
     * Transfer interface
     */
    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> transfer(@RequestBody TransferRequest request) {
        TransferResponse response = bankService.transfer(request);
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Refund interface
     */
    @PostMapping("/refund")
    public ResponseEntity<RefundResponse> refund(@RequestBody RefundRequest request) {
        RefundResponse response = bankService.refund(request);
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }
    
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
     * Get or create my bank account (uses authenticated user's email from token)
     */
    @GetMapping("/account/me")
    public ResponseEntity<BankAccountCreateResponse> getMyAccount(HttpServletRequest request) {
        String userEmail = (String) request.getAttribute("userEmail");

        if (userEmail == null || userEmail.isEmpty()) {
            return ResponseEntity.status(401).body(
                new BankAccountCreateResponse(false, null, "User not authenticated")
            );
        }

        BankAccountCreateResponse resp = bankService.getOrCreateAccountByEmail(userEmail);
        if (resp.isSuccess()) {
            return ResponseEntity.ok(resp);
        }
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


