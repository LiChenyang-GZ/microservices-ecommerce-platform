package com.comp5348.bank.controller;

import com.comp5348.bank.dto.*;
import com.comp5348.bank.service.BankService;
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
     * 转账接口
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
     * 退款接口
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
     * 查询余额接口
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
     * 创建银行账户
     */
    @PostMapping("/account")
    public ResponseEntity<BankAccountCreateResponse> createAccount(@RequestBody BankAccountCreateRequest request) {
        BankAccountCreateResponse resp = bankService.createAccount(request);
        if (resp.isSuccess()) return ResponseEntity.ok(resp);
        return ResponseEntity.badRequest().body(resp);
    }
}


