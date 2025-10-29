package com.comp5348.bank.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequest {
    
    private String fromAccount;
    private String toAccount;
    private BigDecimal amount;
    private String transactionRef;
}


