package com.aesoftwaresolutions.solid.bank;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One transaction read from a statement file.
 *
 * @param fitId  bank-assigned unique id (OFX FITID) or null for CSV
 * @param amount positive = money into the account
 */
record ParsedTransaction(String fitId, LocalDate date, BigDecimal amount, String description) {
}
