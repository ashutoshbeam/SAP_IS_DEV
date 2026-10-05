# Cronacle email to SAP Datasphere

SAP Cloud Integration extraction and JDBC mapping for a configured Cronacle alert mailbox and IMAP folder. Mailbox-specific details are intentionally excluded from this public project.

## Download and import

Download `dist/EmailToDatasphere.iflow.zip` from this repository (use **Download raw file**). In Integration Suite, open your integration package and add an Integration Flow using the upload option.

This is a **configuration-ready iFlow skeleton** containing the extraction and JDBC mapping scripts. The Mail and JDBC channels must be added in your tenant. Its XML structure and local scripts are tested; import, deployment, mailbox polling and SQL execution have **not** been tested in a live SAP tenant.

If your editor rejects the generated skeleton due to a step-version difference, create an iFlow and add the three Groovy scripts in the order below. The same tested mapping code is used in either approach.

```text
Mail (IMAP) -> Start -> ConfigureMapping -> ExtractCronacleAlert
             -> BuildJdbcPayload -> End -> JDBC (SAP HANA Cloud)
```

## Configure in your tenant

1. Connect the Sender participant to Start and select **Mail / IMAP4**. Configure your mailbox connection and credentials. Set your alert folder and confirm the exact IMAP hierarchy delimiter/path on your server.
2. Set Mail processing to deliver the decoded email body with attachments. Do not use attachment-only body mode. Allow the `Date` header through runtime configuration; forwarded alerts normally use their original `Sent:` line instead.
3. Select unread messages. Use **Mark as Read** or archive only after successful processing; configure error retries and monitoring in your tenant. Keep attachment logging/tracing off for production.
4. In `ConfigureMapping.groovy`, replace `REPLACE_WITH_OPEN_SQL_SCHEMA` with your actual writable schema. `TargetTable` defaults to `IBP_Cronacle_Status`, with exact case preserved. These are identifiers, not credentials.
5. Connect End to the Receiver participant and choose **JDBC**. Configure the HANA Cloud JDBC Data Source Alias. Use non-batch XML SQL processing; the mapping emits a `SQL_DML` envelope containing one HANA `MERGE`. Leave prepared-statement/inline-function options off for this literal SQL payload.
6. Validate, deploy, and test a Completed mail and an Error mail before enabling routine polling. If your tenant requires a separate Send or Request Reply step for the JDBC channel, put it after `BuildJdbcPayload` and before End.

## Requested field mapping

| Target | Source / behavior |
|---|---|
| `P_CHAIN` | Exact name between `A Cronacle Chain` or `A Cronacle Process` and `[run number]`; maximum 220 characters |
| `Status` | `Completed` or `Error` from the same alert sentence |
| `Reason` | For Error, full decoded contents of matching ErrorLog attachments; empty for Completed or no matching attachment |
| `Date` | Date of original email alert timestamp, in configured storage timezone |
| `Time` | Full timestamp of original alert, in configured storage timezone, with 7 fractional digits |
| `ID` | Required technical key: deterministic 32-character alphanumeric ID (UUID with hyphens removed) from name, run number and original timestamp |

Only the five requested business fields and mandatory `ID` are sent. `createdAt`, `createdBy`, `modifiedAt`, and the hidden tenth field are omitted. Existing audit fields remain untouched; new rows use database defaults or nulls. If the live table has additional mandatory fields without defaults, the insert will fail until its schema is reconciled.

The screenshot defines the composite primary key as `ID`, `P_CHAIN`, `Date`, `Time`. The generated MERGE matches all four. An existing matching row has `Status` and `Reason` updated; otherwise a new row is inserted with all six mapped columns. Each alert/run therefore has its own record. This does not overwrite every historic row with the same chain name and does not locate manually created rows with unrelated UUIDs. Concurrent delivery of the same new key can still need a retry after a primary-key conflict.

## Parsing and time rules

- Supports plain text and HTML, including bold spans, entities and names containing `&`.
- Exactly one supported Cronacle alert per email is required. Unrecognized or ambiguous emails fail before any database write.
- Reads matching attachment names case-insensitively: `ErrorLog.txt`, `ErrorLog.log`, `Error Log.txt`, `error_log.txt`, and filename suffix variants. Other logs are ignored. Multiple error logs are joined in filename order with filename headings.
- Uses decoded SAP attachment streams; UTF-8 is the default, with UTF-8/UTF-16 BOM detection. Change `AttachmentCharset` for non-UTF text. Invalid bytes fail instead of being silently replaced. Maximum matching attachment size is 1 MiB.
- Uses the nearest `Sent:` line preceding the alert. The English Outlook format in the screenshots is supported, including `(UTC+05:30)`. If a Sent line exists but cannot be parsed, processing fails instead of silently using a different date.
- Direct alerts without `Sent:` use the email RFC `Date` header. No timestamp means failure; there is no fallback to processing time. An optional `EventTimestampOverride` exchange property accepts ISO offset timestamps for controlled reprocessing.
- Defaults: source timezone `Asia/Kolkata`; database storage timezone `UTC`. HANA TIMESTAMP does not carry a timezone, so use the same storage convention as your existing reporting application. Set `StorageTimeZone` to `Asia/Kolkata` if that table stores India local time.
- Example: `October 5, 2026 10:55:23 AM (UTC+05:30)` becomes `Date=2026-10-05`, `Time=2026-10-05 05:25:23.0000000` under the UTC default. This is the notification time, since no separate execution-end timestamp is present in the screenshots.

## Reason length

Your screenshot defines `Reason` as NVARCHAR(2000). Full logs longer than this **cannot fit**. Default `ReasonOverflowPolicy=FAIL` prevents silent loss. To accept shortened reasons, set `ReasonOverflowPolicy=TRUNCATE`; output ends with `[TRUNCATED]` and stays within 2,000 UTF-16 code units. For full longer logs, change the storage design and mapping limit together.

No real error-log content was supplied. Tests use synthetic text; verify one real attachment and charset in your tenant.

## JDBC and Datasphere notes

The target must be a table writable by the configured JDBC database user, normally in its Open SQL schema. A Datasphere UI table name alone does not establish write access. Use the real physical schema/table name, and ensure its columns match the case shown in your screenshot.

Identifiers are validated and double-quoted; text values have SQL apostrophes escaped and XML escaping is handled by the XML builder. The MERGE casts `Date` and `Time` explicitly. No database DDL is run by this project.

## Tests and build

Requires Java 8+ and Groovy 2.4.21 (or a compatible Groovy runtime). From the project directory:

```text
groovy -cp tests tests/RunTests.groovy
python build.py
```

Tests execute the actual scripts with a minimal SAP Message test double. They cover screenshot-based alerts, HTML, attachments, encodings, key stability, timezone conversion, SQL/XML escaping, invalid inputs and length limits. The test double is excluded from the iFlow ZIP. Local tests do not prove SAP editor compatibility or HANA SQL execution.

## SAP references

- [Mail sender configuration](https://help.sap.com/docs/cloud-integration/sap-cloud-integration/configure-mail-sender-adapter)
- [Reading attachments through the Message API](https://help.sap.com/docs/cloud-integration/sap-cloud-integration/read-multiple-attachments)
- [JDBC receiver](https://help.sap.com/docs/integration-suite/sap-integration-suite/jdbc-receiver-adapter)
- [JDBC XML SQL format](https://help.sap.com/saphelp_em700_ehp01/helpdata/en/2e/96fd3f2d14e869e10000000a155106/content.htm)
- [HANA MERGE syntax](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-sql-reference-guide/merge-into-statement-data-manipulation)
- [Datasphere Open SQL schemas](https://help.sap.com/docs/SAP_DATASPHERE/9f804b8efa8043539289f42f372c4862/3de55a78a4614deda589633baea28645.html)
