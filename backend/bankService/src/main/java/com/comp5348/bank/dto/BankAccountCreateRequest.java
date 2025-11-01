package com.comp5348.bank.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BankAccountCreateRequest {
    private String ownerEmail; // or ownerName
    private BigDecimal initialBalance;
}


