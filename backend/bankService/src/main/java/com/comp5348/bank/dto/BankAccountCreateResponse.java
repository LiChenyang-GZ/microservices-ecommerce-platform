package com.comp5348.bank.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BankAccountCreateResponse {
    private boolean success;
    private String accountNumber;
    private String message;
}


