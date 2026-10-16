# 2025 to 2026 caseworker calculator

`2025-26-full-calculation.html` is a single page that runs the HMRC caseworker workbook for the tax year 6 April 2025 to 5 April 2026 (`2026.xlsm`, version V1.5.1). It is an example of that workbook. It does not submit a return and it is not an HMRC service.

The page includes every schedule a caseworker can open: employment, self-employment, property, foreign income, trusts, capital gains, student loans, the high income child benefit charge, and the rest. Each box explains what to enter and which calculation stage uses the figure. The stages underneath list the boxes the workbook calculated. A total stays beside the form while you enter figures.

The arithmetic is the workbook's own formulas, compiled into the page. Rates and allowances are the 2025 to 2026 figures from the workbook's data sheet.

## Run it

From the repository root:

```bash
python3 -m http.server 8765 --directory docs/caseworker-calculator
```

Open http://127.0.0.1:8765/2025-26-full-calculation.html

Python 3 is the only requirement. The Play service, sbt and MongoDB are not used.

Leave the server running and refresh the page after any change to the HTML file.

## What you should see

The page opens on a worked example:

| Box | Amount |
|---|---|
| Pay from employment | £42,000 |
| Tax taken off pay | £5,886 |
| Untaxed UK interest | £800 |
| Dividends from UK companies | £2,000 |
| Gift Aid payments | £200 |

The total tax and National Insurance due is **£131.25**. Income Tax charged is £6,017.25 and tax already paid is £5,886.00.

**Add a capital gain** keeps that example and adds a gain of £20,000. Capital gains tax is £3,736.80 and the total due is £3,868.05.

**Clear the form** removes the amounts. The total returns to £0.00.

**Use the example** puts the worked example back.

**Find a box** filters the schedules. Try `capital gains`, `pre-owned assets` or `goods and assets`.

## Other checks

These are not buttons on the page. Change the fields and the total updates.

A Scottish taxpayer (residency set to Scotland) with the worked example plus taxable self-employment profit of £20,000 comes to **£9,184.85**. That includes Class 4 National Insurance of £445.80.

## What the page covers

The schedules and the order of the stages follow the workbook: non-savings income, savings, dividends, allowances, tax, National Insurance, capital gains, student and postgraduate loans, the high income child benefit charge, residence and the non-resident calculation, foreign tax credit relief, and the taxpayer's own calculation.

Blank amounts count as zero, as they do in the workbook.
