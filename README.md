# Budgetinator

A Mac app that walks you through an Ithaca College SGC budget request and fills in the official Excel
workbook for you: header questions, line items (with the suggested price list), additional funding and
supporting documents. It checks your request against the template's rules before exporting.

## Download

1. Go to the [latest release](https://github.com/robog-two/budgetinator/releases/latest).
2. Download the `.dmg` for your Mac:
   - `Budgetinator-<version>-arm64.dmg` for Apple Silicon (M1 and newer). Most Macs since late 2020 are this one.
   - `Budgetinator-<version>-x64.dmg` for Intel Macs.

   Not sure which? Apple menu → **About This Mac**: if it says "Chip: Apple M…", get arm64.
3. Open the `.dmg` and drag **Budgetinator** onto the **Applications** folder.
4. Launch Budgetinator from Applications or Launchpad.

Requires macOS 12 (Monterey) or newer. The app is signed and notarized by Apple.

> This app is not endorsed by SGC — please check the final spreadsheet before submitting.
> Made with <3 by an IC student.

---

## Development

Kotlin + Compose Multiplatform Desktop, JDK 25.

    sdk use java 25.0.1-tem
    ./gradlew run                          # launch from source
    ./gradlew test                         # validator + exporter tests
    ./gradlew createReleaseDistributable   # ProGuard-shrunk .app in build/compose/binaries/main-release/app
    packaging/package-mac.sh               # release .app + .dmg + .zip in dist/

`packaging/package-mac.sh` signs with your **Developer ID Application** identity if one is in your keychain
(override with `MAC_SIGNING_IDENTITY`, skip with `--no-sign`), and notarizes too if you set `NOTARY_PROFILE`
to a profile created with `xcrun notarytool store-credentials`. Unsigned builds work for local testing.

### Build notes

- **Version**: `appVersion` in `gradle.properties` is the only place the version lives. It feeds `version`,
  the bundle version, and the `jpackage.app-version` system property the app reads at runtime. Release
  builds override it from the tag (`-PappVersion=1.2.3`).
- **ProGuard** runs for the release tasks (`createReleaseDistributable`, `packageReleaseDmg`, `runRelease`).
  Shrinking only, no obfuscation; it drops the unused Material extended icons. App-specific rules are in
  `proguard-rules.pro`. If a release build crashes with `ClassNotFoundException`/`NoSuchMethodError`
  but `./gradlew run` works, add a `-keep` rule there. JDK 25 ships without `jmods/`, so the build
  extracts the JDK classes with `jimage` into `build/proguard-jdk` for ProGuard to link against.
- **Signing** is off by default. `-Pmac.sign=true` (or `MAC_SIGN=true`) turns it on;
  `mac.signing.identity` / `MAC_SIGNING_IDENTITY` and `mac.signing.keychain` / `MAC_SIGNING_KEYCHAIN`
  pick the certificate. Hardened-runtime entitlements are in `packaging/entitlements.plist`.
- **DMG**: `packaging/make-dmg.sh` builds the drag-to-Applications image with `hdiutil` (app plus an
  `Applications` symlink). jpackage's own DMG depends on Finder scripting and leaves the symlink out
  when run headless.

## Releasing

Releases are built by `.github/workflows/release.yml` on GitHub-hosted macOS runners: one Apple Silicon
build (`macos-15`) and one Intel build (`macos-15-intel`). Each is signed, notarized and stapled, and both
are attached to a GitHub Release:

| Asset | Purpose |
|---|---|
| `Budgetinator-<version>-arm64.dmg` / `-x64.dmg` | Download for people |
| `Budgetinator-<version>-arm64.zip` / `-x64.zip` | `ditto` zip of the stapled `.app`, used by the in-app updater |

Keep those names unchanged: the in-app updater looks for them.

### One-time setup

You need an Apple Developer Program membership. Creating a Developer ID certificate requires the
**Account Holder** role, which you have on an individual membership.

**1. Find your Team ID.** Open <https://developer.apple.com/account>, go to **Membership details**, and copy
the 10-character **Team ID** (e.g. `ABCDE12345`).

**2. Create a Developer ID Application certificate.** Use one of these two methods:

- *Xcode (easiest):* Xcode → **Settings…** → **Accounts** → select your Apple ID → select the team →
  **Manage Certificates…** → **+** → **Developer ID Application**. Xcode creates the certificate and puts
  it in your login keychain.
- *Website:* in **Keychain Access**, choose **Keychain Access → Certificate Assistant → Request a
  Certificate From a Certificate Authority…**, enter your email, choose **Saved to disk**, and save the
  `.certSigningRequest`. Then open <https://developer.apple.com/account/resources/certificates/add>, choose
  **Developer ID Application**, profile type **G2 Sub-CA**, upload the request, download the `.cer`, and
  double-click it to install it.

**3. Export the certificate as a .p12.** In Keychain Access, open the **login** keychain → **My
Certificates**. Right-click **Developer ID Application: Your Name (TEAMID)**. It needs a disclosure
triangle with the private key under it; without the key the export won't work. Choose **Export…**, pick the
**Personal Information Exchange (.p12)** format, save as `cert.p12`, and set a strong password.

**4. Create an App Store Connect API key for notarization.** Open
<https://appstoreconnect.apple.com/access/integrations/api>. The first time, click **Request Access** and
accept the terms. Under **Team Keys**, click **Generate API Key** (or **+**), name it `Budgetinator notarization`,
choose the **Developer** access role, and click **Generate**. Then:
- Click **Download** to get `AuthKey_<KEYID>.p8`. Apple only lets you download it once, so keep a copy somewhere safe.
- The **Key ID** is in the key's row in the table.
- The **Issuer ID** (a UUID) is shown above the table.

**5. Add the secrets to GitHub.** Run these from the folder holding the files, after the repo exists
(`gh auth login` first if needed):

```sh
REPO=robog-two/budgetinator
base64 -i cert.p12 | gh secret set MACOS_CERTIFICATE_P12_BASE64 --repo $REPO
gh secret set MACOS_CERTIFICATE_PASSWORD --repo $REPO          # prompts for the .p12 password
gh secret set APPLE_TEAM_ID --repo $REPO --body ABCDE12345
base64 -i AuthKey_XXXXXXXXXX.p8 | gh secret set APPLE_API_KEY_P8_BASE64 --repo $REPO
gh secret set APPLE_API_KEY_ID --repo $REPO --body XXXXXXXXXX
gh secret set APPLE_API_ISSUER_ID --repo $REPO --body 00000000-0000-0000-0000-000000000000
```

You can also paste values in the web UI (repo → **Settings → Secrets and variables → Actions → New repository
secret**). For the base64 ones, `base64 -i cert.p12 | pbcopy` copies the value to the clipboard.

| Secret | Value |
|---|---|
| `MACOS_CERTIFICATE_P12_BASE64` | base64 of the exported `.p12` |
| `MACOS_CERTIFICATE_PASSWORD` | the `.p12` export password |
| `APPLE_TEAM_ID` | Team ID; picks the matching "Developer ID Application: … (TEAMID)" identity |
| `APPLE_API_KEY_P8_BASE64` | base64 of `AuthKey_<KEYID>.p8` |
| `APPLE_API_KEY_ID` | API key ID |
| `APPLE_API_ISSUER_ID` | API issuer ID |

Delete `cert.p12` and keep the `.p8` somewhere safe (a password manager works). To test signing and
notarization without publishing a release, run the workflow by hand: **Actions → Release → Run workflow**.
The `.dmg`/`.zip` files are attached to the run as artifacts.

### Cutting a release

```sh
# optional: bump appVersion in gradle.properties so local builds match
git tag v1.0.1
git push origin v1.0.1
```

The tag must be `v` + a version of 1–3 numbers (`v1.0.1`). The workflow builds both architectures, then
creates the GitHub Release `v1.0.1` with auto-generated notes and the four assets. It takes about 15–30
minutes, most of it waiting for Apple's notary service.

---

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

## License

MIT, see [LICENSE](LICENSE). The bundled Carlito font is under the SIL Open Font License 1.1
([OFL.txt](src/main/resources/fonts/OFL.txt)).
