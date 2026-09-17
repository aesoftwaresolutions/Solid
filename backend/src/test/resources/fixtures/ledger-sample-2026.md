# Golden sample ledger — sole proprietor, calendar fiscal year, USD

Hand-computed expected results for spec 006. **Do not change expected numbers without owner approval.**

## Transactions (Dr = debit, Cr = credit)

| # | Date | Description | Dr | Cr | Amount |
|---|---|---|---|---|---|
| 1 | 2025-12-15 | Prior-year service revenue | 1010 Checking | 4010 Revenue | 400.00 |
| 2 | 2026-01-02 | Owner contribution | 1010 Checking | 3010 Owner's Contributions | 5,000.00 |
| 3 | 2026-01-15 | Service revenue (cash) | 1010 Checking | 4010 Revenue | 2,500.00 |
| 4 | 2026-01-20 | Software subscription | 6220 Software | 1010 Checking | 54.99 |
| 5 | 2026-02-01 | Advertising on credit card | 6010 Advertising | 2010 Credit Card | 300.00 |
| 6 | 2026-02-10 | Owner draw | 3020 Owner's Draws | 1010 Checking | 1,000.00 |
| 7 | 2026-02-15 | Customer refund | 4020 Returns & Allowances | 1010 Checking | 100.00 |
| 8 | 2026-03-01 | Equipment purchase | 1500 Equipment | 1010 Checking | 1,200.00 |
| 9 | 2026-03-05 | Pay credit card | 2010 Credit Card | 1010 Checking | 300.00 |
| 10 | 2026-03-10 | Revenue on account | 1100 Accounts Receivable | 4010 Revenue | 800.00 |
| 11 | 2026-03-15 | Supplies (mistake) — then **reversed** | 6160 Supplies | 1010 Checking | 75.00 |
| 12 | 2026-12-31 | Depreciation | 6050 Depreciation | 1510 Accumulated Depreciation | 240.00 |
| — | 2026-04-01 | **Draft** (never posted) misc expense | 6900 Other | 1010 Checking | 999.00 |

## Expected trial balance as of 2026-12-31

| Code | Debit | Credit |
|---|---|---|
| 1010 | 5,245.01 | |
| 1100 | 800.00 | |
| 1500 | 1,200.00 | |
| 1510 | | 240.00 |
| 3010 | | 5,000.00 |
| 3020 | 1,000.00 | |
| 4010 | | 3,700.00 |
| 4020 | 100.00 | |
| 6010 | 300.00 | |
| 6050 | 240.00 | |
| 6220 | 54.99 | |
| **Total** | **8,940.00** | **8,940.00** |

Checking: 400 + 5,000 + 2,500 − 54.99 − 1,000 − 100 − 1,200 − 300 − 75 + 75 = 5,245.01. Credit card nets to 0 (omitted). Supplies nets to 0 (omitted).

## Expected P&L 2026-01-01 → 2026-12-31

- Income: 4010 3,300.00; 4020 −100.00 → **3,200.00**
- COGS: none → 0.00; Gross profit **3,200.00**
- Expenses: 6010 300.00; 6050 240.00; 6220 54.99 → **594.99**
- **Net income 2,605.01**

## Expected balance sheet as of 2026-12-31

- Assets: 1010 5,245.01; 1100 800.00; 1500 1,200.00; 1510 −240.00 → **7,005.01**
- Liabilities: → **0.00**
- Equity: 3010 5,000.00; 3020 −1,000.00; Retained Earnings (prior years) 400.00; Current Year Earnings 2,605.01 → **7,005.01**
- Balanced: **true**
