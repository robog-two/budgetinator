# Budgetinator

Compose Desktop (Kotlin, JDK 25) wizard that fills in the Ithaca College SGC budget request workbook.

    sdk use java 25.0.1-tem
    ./gradlew run      # launch
    ./gradlew test     # validator + exporter tests

## How it works

| Layer | Where | Notes |
|---|---|---|
| Reference data | `model/Reference.kt` | Dropdown lists from the hidden sheet, the suggested price list, thresholds |
| Normalized model | `model/Request.kt`, `model/BudgetStore.kt` | In-memory; items, funding sources and documents are separate rows linked by id |
| Rules | `validation/Validator.kt` | Pure function `BudgetRequest -> List<Issue>`; ERRORs block export, WARNINGs don't |
| Export | `export/XlsxExporter.kt` | Patches the bundled template's XML in place (no workbook rebuild), so styles, tables, dropdowns, conditional formatting and the embedded instructions are preserved |
| UI | `ui/` | 6-step wizard: basics, details, items, funding, documents, review/export |

The template is bundled at `src/main/resources/SGC_New_request_template.xlsx` (an unmodified copy).

## Rules extracted from the template

**Header (Budget sheet, D2:D9)** — all required: request type (8 options: Operational / Programming / Travel /
Fundraising, each with a "next academic year Block 1" variant), organization, event name, date, location,
held-before Y/N, College Services Y/N, additional funding Y/N.

**Conditional questions (from the sheet's conditional formatting)**
- Semester (F2): Operational and Fundraising types only.
- People on trip (F4): Travel types only.
- Expected attendance (F5): Programming types only.
- Past attendance and past cost (F6:F7): only when "held before" = Yes.
- College Services = Yes: "Include invoices from them with your supporting documentation".
  The unlabeled D11 dropdown (Print Shop / CCES / Public Safety / Other) is filled from the service chips.

**Items (rows 13+, Table1)** — each needs type (8 options), name, purchase location, source/reasoning,
quantity (whole number >= 1), amount per item (> $0). Total = qty × amount; the grand total (F11) is
`ROUNDUP(sum, 0)`. The template ships 22 rows and the instructions say to Tab to add more — the exporter grows
the table (and its dropdown validations) as needed.

**Additional funding (step 7 / D10)** — Programming requests with a grand total *over* $1,000 must list
additional fundraising sources. Each source needs source, rationale, status (Pending/Approved/Denied) and amount.
Travel/fundraising requests with outside money should list it. "Yes" requires at least one row; "No" with rows
listed is a contradiction.

**Supporting documentation (step 6)** — images only (Excel can embed PNG/JPG/GIF). Warns when none attached;
errors when College Services are used and nothing is attached.

**Price list** — picking a suggested item fills type/location/price; starred items (cups, plates, napkins,
cutlery) warn "if included by food provider, not funded"; unit prices above the suggestion warn.

## Known limits
- No draft save/load yet — closing the window discards the request.
- The template's E9 cell still says to see the tab "Fundraising"; the sheet is actually named "Additional Funds".
  Left as-is because the template is not to be altered.
