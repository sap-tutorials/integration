---
parser: v2
author_name: rojo-ashishkuvvarapu
author_profile: https://github.com/rojo-ashishkuvvarapu
keywords: tutorial
auto_validation: true
time: 60
tags: [ tutorial>intermediate, software-product>sap-integration-suite, software-product>sap-business-technology-platform ]
primary_tag: software-product>sap-integration-suite
---

# SAP TechEd Developer Garage: AWS Pipeline for Revenue Management

<!-- description --> Build an integration flow in SAP Integration Suite that polls a sales file from Amazon S3, looks up unit costs in a reference file in S3, calculates profit margin, and writes the enriched data to a second S3 bucket in another region.

## Prerequisites

- An SAP BTP account or trial account with **SAP Integration Suite** and the **Cloud Integration** capability activated
- [Assign roles to your user for Cloud Integration](https://help.sap.com/docs/integration-suite/sap-integration-suite/configuring-user-access?locale=en-US&state=PRODUCTION&version=CLOUD#loio2c6214a3228e4b4cba207f49fda92ed4__section_xbf_glz_l2c), including the `Integration_Developer` role collection
- The file `From the Instructor.txt`, which your instructor shares with you through a secure medium. It is not part of this repository. It contains the **AWS Access Key ID** and **Secret Access Key** for the S3 buckets, plus the bucket, region, and folder names
- Familiarity with the SAP Integration Suite interface helps. No Groovy experience is required; the script is provided.

You don't need an AWS account. Your instructor prepares the S3 buckets, the reference file, and the access keys, and places one sales file in the shared `daily/` folder. The file stays there, so your flow reprocesses it on every poll.

## You will learn

- How to build an integration flow from scratch in SAP Integration Suite
- How to store AWS credentials as Security Material
- How the AmazonWebServices adapter polls, reads, and writes files in Amazon S3
- How to enrich messages with a reference-data lookup and calculate profit margin in Groovy
- How to deploy an integration flow and follow a message step by step in the Monitor

## Scenario

**Company:** A retailer with regional stores

**Challenge:** Every store drops its sales extract (CSV) into an Amazon S3 "landing" bucket. Finance wants profit margin per sale, but the sales file only contains prices. Unit costs live in a separate reference file.

**Data sources:**

| Source | Location | Content |
| :--- | :--- | :--- |
| Sales file | `regional-landing-bkt/daily/` (region `eu-north-1`) | One row per sale: store, region, product, quantity, price |
| Reference file | `regional-landing-bkt/reference/product_master.csv` (region `eu-north-1`) | Unit cost of each of the 15 products |
| Curated output | `curated-corp/profit-margins/` (region `us-west-1`) | The enriched sales data, written by your flow |

**Goal:** Calculate profit margin, a number that exists nowhere in the source file, for every sale, write the enriched data to the curated bucket, and summarize the run.

The AmazonWebServices adapter is the hero of this flow. You use it three ways: polling for new files, reading one file on demand, and writing the result.

The flow you'll build:

```text
S3 sender (poll daily/, keep the file and process it again)
  -> CSV to XML Converter     (CSV -> structured XML, using xml_schema.xsd)
  -> XML to JSON Converter    (XML -> JSON)
  -> Prepare for Lookup       (Content Modifier: ingestion metadata, stash the sales JSON)
  -> Cost LookUp              (Request Reply -> S3 read: product_master.csv)
  -> Margin Calculation       (Groovy: profit margin per sale + run summary)
  -> S3 Write                 (Request Reply -> S3 write: curated-corp/profit-margins/)
  -> Show Run Summary         (Content Modifier: run summary in the body)
  -> End
```

| Part of the flow | What it does |
| :--- | :--- |
| **S3 sender** | Polls the `daily/` folder for the sales CSV and leaves the file in place, so it is processed again on every poll |
| **CSV to XML Converter** | Gives each row named fields, using the `xml_schema.xsd` schema |
| **XML to JSON Converter** | Turns the XML into JSON, the shape the rest of the flow works with |
| **Prepare for Lookup** | Records when and where the file was ingested, and sets the sales data aside so it survives the next step |
| **Cost LookUp** | Reads `product_master.csv` from `regional-landing-bkt/reference/` |
| **Margin Calculation** | Adds unit cost, gross margin, and margin percentage to each sale, and builds the run summary |
| **S3 Write** | Writes the enriched JSON to `curated-corp/profit-margins/` |
| **Show Run Summary** | Puts the run summary in the message, so it's the last thing you see in the Monitor |

---

### Create a Package and an Integration Flow

In SAP Integration Suite, go to **Design → Integrations and APIs** and click **Create**.

| Field | Value |
| :--- | :--- |
| Name | `AWS Pipeline for Revenue Management` |
| Technical Name | *(auto-populated)* |
| Short Description | `Ingest and enrich sales data in Amazon S3` |

Click **Save**.

On the package's **Artifacts** tab, click **Add → Integration Flow**. Keep **Create** selected and fill in:

| Field | Value |
| :--- | :--- |
| Name | `AWS Pipeline for Revenue Management` |
| ID | *(auto-populated)* |

Click **OK**, open the integration flow, and click **Edit**. The canvas starts with a **Sender**, a **Start** event, an **End** event, and a **Receiver**.

> **Tip:** You add steps from the palette at the top of the canvas. Converters, Content Modifier, and Groovy Script are under **Message Transformers**; Request Reply is under **Call → External Call**; extra receivers are under **Participants**. Menu names can differ slightly between tenant versions.

Click **Save** regularly as you build.

---

### Create Security Material

Your instructor shares `From the Instructor.txt` with you through a secure medium. It holds the `access key id` and `secret access key`. Store them once; every AWS connection in your flow refers to them by name.

Go to **Monitor → Integrations and APIs → Security Material**. Create two secure parameters, one after the other, with **Create → Secure Parameter**:

| Name | Secure Parameter |
| :--- | :--- |
| `S3Key` | AWS Access Key ID |
| `S3Secret` | AWS Secret Access Key |

For each one, enter the value in **Secure Parameter** and again in **Repeat Secure Parameter**, then click **Deploy**.

> **Caution:** Use the exact names `S3Key` and `S3Secret`, including capitals. The AWS adapters look the secure parameters up by these names (as aliases); a mismatch makes the deployment fail. Never paste the keys into chat, slides, screenshots, or a shared file.

---

### Connect the S3 Sender

Drag a connection from the **Sender** to the **Start** event. Choose adapter **AmazonWebServices** and message protocol **S3**.

Fill in the adapter settings:

| Tab | Field | Value |
| :--- | :--- | :--- |
| Connection | Host | `amazonaws.com` |
| Connection | Region Name | `eu-north-1 Europe (Stockholm)` (choose from the dropdown) |
| Connection | Bucket Name | `regional-landing-bkt` |
| Connection | Polling Interval (in ms) | `300000` (every 5 minutes; each poll is a run) |
| Connection | Authentication Type | `Access and Secret Key` |
| Connection | Access Key Alias | `S3Key` |
| Connection | Secret Key Alias | `S3Secret` |
| Connection | Requester Pays | Unchecked |
| Connection | Enable Legacy Connectivity | Unchecked |
| Processing | Directory | `daily` |
| Processing | File Name | `sales*.csv` |
| Processing | Sorting | `None` |
| Processing | Include Sub-Directories | Unchecked |
| Processing | Execute Post-Processing When Message Successfully Processed | Unchecked |
| Processing | Post-Processing | `Keep File and Process Again` |
| Processing | Optional S3 Metadata | Leave empty |
| Processing | S3 Pre-Signed URL | Unchecked |
| Duplicate Handling | Duplicate Check | Unchecked |

![S3 sender — Connection tab](./assets/aws-s3-step3-sender-connection.png)

![S3 sender — Processing tab](./assets/aws-s3-step3-sender-processing.png)

> **Caution:** Choose the region from the **Region Name** dropdown. A region label typed or pasted into a field makes the adapter fail with an `UnknownHostException`.

The file is never moved or deleted, so every poll processes the same sales file again. You may see the design-time warning "AWS_SND_HTTP_S3_PULL may not pass Text message to CSV To XML Converter". It's an advisory and doesn't stop the flow.

### Convert the CSV to JSON

#### CSV to XML Converter

Add a **CSV to XML Converter** right after the **Start** event. On its **Processing** tab, click **Select** next to **XML Schema** and upload `xml_schema.xsd`. If you don't have the file, save the XML below as `xml_schema.xsd` and upload that.

| Field | Value |
| :--- | :--- |
| XML Schema | `xml_schema.xsd` |
| Path to Target Element in XSD | `/Records/Row` |
| Record Marker in CSV | *(leave blank)* |
| Field Separator in CSV | `Comma` |
| Exclude First Line Header | ✓ |

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
  <xs:element name="Records">
    <xs:complexType>
      <xs:sequence>
        <xs:element name="Row" maxOccurs="unbounded">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="transaction_id" type="xs:string"/>
              <xs:element name="store_id" type="xs:string"/>
              <xs:element name="region" type="xs:string"/>
              <xs:element name="product_id" type="xs:string"/>
              <xs:element name="product_name" type="xs:string"/>
              <xs:element name="category" type="xs:string"/>
              <xs:element name="quantity" type="xs:string"/>
              <xs:element name="unit_price" type="xs:string"/>
              <xs:element name="total_amount" type="xs:string"/>
              <xs:element name="sale_timestamp" type="xs:string"/>
              <xs:element name="payment_method" type="xs:string"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:sequence>
    </xs:complexType>
  </xs:element>
</xs:schema>
```

![CSV to XML Converter — Processing tab](./assets/aws-s3-step4-csv-to-xml.png)

> The converter matches the CSV's header row against the XSD element names exactly, so the sales file's header must be lowercase: `transaction_id,store_id,region,product_id,product_name,category,quantity,unit_price,total_amount,sale_timestamp,payment_method`. The files your instructor's feeder generates already are.

#### XML to JSON Converter

Add an **XML to JSON Converter** after it:

| Field | Value |
| :--- | :--- |
| Use Namespace Mapping | *(unchecked; the XSD declares no namespace)* |
| Suppress JSON Root Element | *(unchecked; keeps the `{"Records":{"Row":[...]}}` shape the script expects)* |
| Other settings | *(defaults)* |

![XML to JSON Converter — Processing tab](./assets/aws-s3-step4-xml-to-json.png)

This converter writes every value as a JSON string. The Groovy script in Step 7 turns the numbers back into real numbers.

### Prepare for the Lookup

Add a **Content Modifier** after the XML to JSON Converter and name it `Prepare for Lookup`. On its **Exchange Property** tab, add four rows:

| Action | Name | Source Type | Source Value | Data Type |
| :--- | :--- | :--- | :--- | :--- |
| Create | `businessDate` | Expression | `${date:now:yyyy-MM-dd}` | |
| Create | `ingestedAt` | Expression | `${date:now:yyyy-MM-dd'T'HH:mm:ss'Z'}` | |
| Create | `sourceRegion` | Constant | `eu-north-1` | |
| Create | `preEnrichmentJson` | Expression | `${body}` | `java.lang.String` |

![Prepare for Lookup — Exchange Property tab](./assets/aws-s3-step5-prepare-for-lookup.png)

- `businessDate`, `ingestedAt`, and `sourceRegion` record when and where the file was ingested.
- `businessDate` also names the curated file (`sales_<businessDate>.json`). Running the flow twice on the same day overwrites that day's file.
- `preEnrichmentJson` sets the sales data aside, because the next step replaces the message body with the cost file.

> **Caution:** Set the Data Type of `preEnrichmentJson` to `java.lang.String`. Without it, the property only points at the body's data stream, the lookup in the next step reads that stream to the end, and the script later finds the property empty ("Text must not be null or empty").

### Look Up Unit Costs from S3

Unit costs live in a reference file your instructor placed in S3: `regional-landing-bkt/reference/product_master.csv`. You read it mid-flow with the same AWS adapter, this time as a receiver.

Add a **Request Reply** after Prepare for Lookup and name it `Cost LookUp`. Add a **Receiver** from the palette (its default name is `Receiver1`). Connect **Cost LookUp** to it and choose **AmazonWebServices**, protocol **S3**.

| Field | Value |
| :--- | :--- |
| Host | `amazonaws.com` |
| Region Name | `eu-north-1 Europe (Stockholm)` (choose from the dropdown) |
| Bucket Name | `regional-landing-bkt` |
| Authentication Type | `Access and Secret Key` |
| Access Key Alias | `S3Key` |
| Secret Key Alias | `S3Secret` |
| Operation | `Read` |
| Directory | `reference` |
| File Name | `product_master.csv` |
| Post-Processing | `Keep File` |

![Cost LookUp — S3 receiver, Read operation](./assets/aws-s3-step6-cost-lookup.png)

> **Caution:** Post-Processing defaults to **Delete File**. Change it to **Keep File**. With the default, the read fails, because you're only allowed to read the reference folder.

After this step, the message body is the cost file itself:

```text
product_id,unit_cost
P-1001,11.99
P-1002,23.99
P-1003,5.99
...
```

The sender waits for files to arrive; this receiver fetches one specific file when the flow asks for it. Same adapter, two different jobs.

### Calculate Profit Margin

Add a **Groovy Script** step after Cost LookUp and name it `Margin Calculation`. Click the step's create-script icon, replace the template in the editor with the script below, and click **OK**.

![Margin Calculation — Groovy script editor](./assets/aws-s3-step7-groovy-editor.png)

It's the only code in the flow. It:

- reads the sales data back out of `preEnrichmentJson`,
- builds a cost lookup from the CSV in the body,
- adds `unit_cost`, `gross_margin`, and `margin_percentage` to every sale,
- stores a run summary in the `runHeadline` and `runSummary` properties.

```groovy
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// This script assumes the CSV->XML Converter and XML to JSON Converter have
// already run, and that the "Prepare for Lookup" Content Modifier stashed
// the resulting JSON in the "preEnrichmentJson" property (along with
// businessDate, ingestedAt and sourceRegion) before the Cost LookUp read
// replaced the message body with product_master.csv.
//
// This script's job is to compute profit margin per transaction using unit
// costs from the reference file in S3, and to leave a run summary in the
// runHeadline and runSummary properties, which participants read in the
// message trace.

Message processData(Message message) {

    // 1. Pull back the JSON built by the XML to JSON Converter. The SAP CI
    // Message wrapper only exposes single-arg getProperty() (no
    // type-conversion overload like raw Camel's Message), and the property
    // can come back as several different underlying types depending on
    // Camel's stream caching -- a String, a raw byte[], or an
    // InputStreamCache (a re-readable stream wrapper). Calling toString()
    // on that last one just prints the Java object identity
    // ("org.apache.camel.converter.stream.InputStreamCache@..."), not its
    // content, so each case needs handling explicitly.
    def rawPreEnrichmentJson = message.getProperty("preEnrichmentJson")
    def preEnrichmentJson
    switch (rawPreEnrichmentJson) {
        case byte[]:
            preEnrichmentJson = new String((byte[]) rawPreEnrichmentJson, "UTF-8")
            break
        case InputStream:
            // The property can share one stream with the body. If a step in
            // between (such as the Cost LookUp read) has already read that
            // stream to the end, rewind it first, or getText() returns "".
            try { rawPreEnrichmentJson.reset() } catch (ignored) { }
            preEnrichmentJson = rawPreEnrichmentJson.getText("UTF-8")
            break
        default:
            preEnrichmentJson = rawPreEnrichmentJson as String
    }
    if (!preEnrichmentJson?.trim()) {
        throw new IllegalStateException("Property preEnrichmentJson is empty. In 'Prepare for Lookup', " +
            "set the Data Type of preEnrichmentJson to java.lang.String so it holds a copy of the sales JSON.")
    }
    def parsedPayload = new JsonSlurper().parseText(preEnrichmentJson)

    // 1a. The XML to JSON Converter mirrors the XML element names exactly,
    // so the rows live at Records.Row -- there is no flat "records" key.
    // Confirm this shape in your own Message Processing Log; if your
    // converter's settings ever produce something different, adjust this
    // line to match.
    def rawRows = parsedPayload.Records.Row
    // Guard against the single-record edge case: some XML->JSON conversions
    // collapse a lone repeating element into a single object instead of a
    // one-element array, which would otherwise break the .each{} below.
    def rows = rawRows instanceof List ? rawRows : [rawRows]

    // 1b. Attach the ingestion metadata from the properties set in
    // "Prepare for Lookup". This also reshapes the payload into the flat
    // { "source-region", "ingested-at", "records" } structure used
    // everywhere else in this lab, instead of the nested Records.Row shape
    // that only exists because that's how the XML happened to be structured.
    def payload = [
        "source-region": message.getProperty("sourceRegion"),
        "ingested-at"  : message.getProperty("ingestedAt"),
        "records"      : rows
    ]

    // 2. Parse the reference file read from S3 by Cost LookUp (the current
    // body): regional-landing-bkt/reference/product_master.csv, with a
    // header line "product_id,unit_cost" followed by one product per line.
    // The header line is skipped, which also skips any byte-order mark a
    // spreadsheet tool may add. Splitting on comma or semicolon covers Excel
    // on European locales, which saves CSVs with semicolons.
    def costLookup = [:]
    message.getBody(java.io.Reader).readLines().drop(1).findAll { it.trim() }.each { line ->
        def cols = line.split(/[,;]/)
        costLookup[cols[0].trim()] = new BigDecimal(cols[1].trim())
    }

    // 3. The one genuinely custom piece of business logic left in code:
    // derive profit margin, a number that doesn't exist anywhere in the
    // source CSV, from cost data that only lives in the reference file.
    payload.records.each { record ->
        def unitCost = costLookup[record.product_id]
        def quantity = record.quantity as BigDecimal
        def totalAmount = record.total_amount as BigDecimal
        def unitPrice = record.unit_price as BigDecimal

        // Write the numeric casts back onto the record. The XML to JSON
        // Converter serializes every XML text node as a JSON string, so
        // without this quantity/unit_price/total_amount would stay quoted
        // in the curated output.
        record.quantity = quantity
        record.unit_price = unitPrice
        record.total_amount = totalAmount

        if (unitCost != null) {
            def cost = unitCost * quantity
            def margin = totalAmount - cost
            record.unit_cost = unitCost
            record.gross_margin = margin.setScale(2, BigDecimal.ROUND_HALF_UP)
            record.margin_percentage = totalAmount == 0 ? 0 :
                (margin / totalAmount * 100).setScale(1, BigDecimal.ROUND_HALF_UP)
        } else {
            record.unit_cost = "unknown"
            record.gross_margin = "unknown"
            record.margin_percentage = "unknown"
        }
    }

    // 4. Build a short run summary. Nothing here changes the curated JSON --
    // it only sets two exchange properties, runHeadline and runSummary,
    // which show up in Monitor -> the message's trace -> Properties for any
    // step after this one. Edit the two path constants if your buckets differ.
    // The sender keeps the source file in place (Post-Processing: Keep File
    // and Process Again), so every poll reprocesses the same file.
    def sourcePath  = "regional-landing-bkt/daily/ (kept for the next run)"
    def curatedPath = "curated-corp/profit-margins/sales_" + message.getProperty("businessDate") + ".json"

    def priced = payload.records.findAll { it.gross_margin instanceof Number }
    def unpricedCount = payload.records.size() - priced.size()
    def totalRevenue = priced.sum(BigDecimal.ZERO) { it.total_amount as BigDecimal }
    def totalMargin  = priced.sum(BigDecimal.ZERO) { it.gross_margin as BigDecimal }
    def marginPct = totalRevenue == 0 ? BigDecimal.ZERO :
        (totalMargin / totalRevenue * 100).setScale(1, BigDecimal.ROUND_HALF_UP)

    def marginBy = { String field ->
        priced.groupBy { it[field] }.collectEntries { key, recs ->
            [key, recs.sum(BigDecimal.ZERO) { it.gross_margin as BigDecimal }]
        }
    }
    def topProduct = priced ? marginBy("product_name").max { it.value } : null
    def topRegion  = priced ? marginBy("region").max { it.value } : null

    // Locale.US pins the format to 1,234.56 regardless of the tenant JVM's
    // default locale (which could otherwise render 1.234,56).
    def money = { BigDecimal v -> "\$" + String.format(Locale.US, "%,.2f", v) }

    def lines = []
    lines << "Profit margin run complete"
    lines << ""
    lines << "Business date:  " + message.getProperty("businessDate")
    lines << "Records:        " + payload.records.size() + " (" + unpricedCount + " without cost data)"
    lines << "Revenue:        " + money(totalRevenue)
    lines << "Gross margin:   " + money(totalMargin) + " (" + marginPct + "%)"
    if (topProduct) {
        lines << ""
        lines << "Top product:    " + topProduct.key + " (" + money(topProduct.value) + " margin)"
        lines << "Top region:     " + topRegion.key + " (" + money(topRegion.value) + " margin)"
    }
    lines << ""
    lines << "Read from:      " + sourcePath
    lines << "Published to:   " + curatedPath

    message.setProperty("runHeadline",
        "Profit margins ready: " + message.getProperty("businessDate") + " | " + marginPct + "% gross margin")
    message.setProperty("runSummary", lines.join("\n"))

    message.setBody(JsonOutput.toJson(payload))
    message.setHeader("Content-Type", "application/json")
    return message
}
```

> The **Read from** and **Published to** lines in the summary use fixed paths. If your folders differ from the defaults, edit the `sourcePath` and `curatedPath` lines in the script. The flow itself doesn't depend on them.

### Write the Curated File and Show the Summary

#### S3 Write

Add a **Request Reply** after Margin Calculation and name it `S3 Write`. Connect it to the **Receiver** that came with the canvas and choose **AmazonWebServices**, protocol **S3**.

| Field | Value |
| :--- | :--- |
| Host | `amazonaws.com` |
| Region Name | `us-west-1 US West (N. California)` (choose from the dropdown) |
| Bucket Name | `curated-corp` |
| Authentication Type | `Access and Secret Key` |
| Access Key Alias | `S3Key` |
| Secret Key Alias | `S3Secret` |
| Operation | `Write` |
| Directory | `profit-margins` |
| File Name | `sales_${property.businessDate}.json` |
| Content Type | `application/json` |
| Existing File Handling | `Overwrite` |

![S3 Write — S3 receiver, Write operation](./assets/aws-s3-step8-write-curated.png)

#### Show Run Summary

Add a **Content Modifier** after S3 Write, name it `Show Run Summary`, and connect it to the **End** event:

| Tab | Setting | Value |
| :--- | :--- | :--- |
| Message Body | Type | `Expression` |
| Message Body | Body | `${property.runSummary}` |
| Message Header | `Content-Type` | Constant: `text/plain` |

After the S3 write, the body is only S3's acknowledgement. This step puts the run summary there instead, so the last thing you see in the Monitor is the result of the whole run.

Your finished flow:

![Finished integration flow](./assets/aws-s3-step8-finished-iflow.png)

---

### Deploy and Test

Click **Save**, then **Deploy**. Go to **Monitor → Integrations and APIs → Manage Integration Content** and wait until your integration flow shows **Started**.

In the same view, select your integration flow and set its **Log Level** to **Trace**. Trace switches itself off after a short while, so do this right before testing.

Your flow processes the sales file in `daily/` on every poll, so a run starts within a few minutes of deploying. Open **Monitor Message Processing** and wait for your message to show **Completed**. Open it and click **Message Content**. The view shows the message *before* the step you select.

| Check | Where | What you should see |
| :--- | :--- | :--- |
| Picked up | Monitor Message Processing | Your message appears. The file stays in `daily/` and is picked up again on the next poll |
| Enriched | **S3 Write** → Payload | Every sale has `unit_cost`, `gross_margin`, and `margin_percentage` |
| Summarized | **End 1** → Exchange Properties | `runSummary` holds the run summary |

![Monitor Message Processing — message Completed](./assets/aws-s3-step9-monitor-completed.png)

![Message Content — run summary at End 1](./assets/aws-s3-step9-run-summary.png)

This example comes from a test run with the 1,000-row sample file `sales_2026-10-07.csv`:

```text
Profit margin run complete

Business date:  2026-10-07
Records:        1000 (0 without cost data)
Revenue:        $91,022.76
Gross margin:   $37,338.94 (41.0%)

Top product:    Standing Desk ($6,125.00 margin)
Top region:     Northeast ($9,240.39 margin)

Read from:      regional-landing-bkt/daily/ (kept for the next run)
Published to:   curated-corp/profit-margins/sales_<today's date>.json
```

> Every run processes the same 1,000-row file, so your numbers match the example. **Records** should equal the number of rows in the file, and **0 without cost data** confirms every product was found in the reference file.

#### Troubleshooting

| Issue | Fix |
| :--- | :--- |
| `UnknownHostException: s3.Europe (Stockholm)...` | The region was entered as a label instead of chosen from the dropdown. Select `eu-north-1 Europe (Stockholm)`. |
| Deployment fails mentioning a credential | The secure parameter names don't match. They must be exactly `S3Key` and `S3Secret`, deployed, and entered in the Access Key Alias and Secret Key Alias fields of all three AWS adapters. |
| `Header TRANSACTION_ID not present in CSV headers` | The sales file's header doesn't match the XSD. It must be the lowercase header shown in Step 4. |
| Cost LookUp: access denied | Post-Processing must be **Keep File**, not Delete File. If it is, ask your instructor to check the reference folder permission. |
| Cost LookUp: file not found | Directory must be `reference` and File Name `product_master.csv`, exactly, in Region `eu-north-1`. |
| `Text must not be null or empty` (Margin Calculation) | The stashed sales data is empty. Set the Data Type of `preEnrichmentJson` to `java.lang.String` in Prepare for Lookup. |
| `JsonException ... current character read is '<'` | The stashed property holds XML. The XML to JSON Converter is missing, or Prepare for Lookup sits before it. |
| Every record shows `unit_cost` "unknown" | The lookup found no costs. Check in Message Content that the body after Cost LookUp starts with `product_id,unit_cost`. |
| Curated file is named `sales_.json` | `businessDate` is missing. Add it in Prepare for Lookup (Step 5). |
| Script check: JSONSlurper without streaming | A best-practice warning, not an error. The script already reads the body as a stream. |
| No Message Content in the Monitor | Trace wasn't active when the file was processed. Set the log level to Trace and wait for the next poll. |
| My message never appears | Check your flow is **Started** in Manage Integration Content, and that the sender Directory is `daily`. |

#### Try It Yourself

- Before you open the summary, predict which region earned the most margin. Then check.
- Ask your instructor to raise the unit cost of the Standing Desk in `product_master.csv`. The next run's margin drops, even though the sales data didn't change.
- Ask your instructor to replace the file in `daily/` with one that contains a sale of product `P-9999`. The summary reports one record without cost data, and that record shows `unknown` for its margin.

You built a flow that polls Amazon S3, enriches the data from a reference file in S3, writes the result back to S3 in another region, and summarizes the run.

#### References

- [Amazon Web Services Adapter Guide for SAP Cloud Integration](https://hub.sap.com/odata/1.0/catalog.svc/Files('10541079cd834d69a3fbd978c85b11bd')/$value)
- [Amazon Web Services Adapter for SAP Cloud Integration (SAP Community)](https://blogs.sap.com/2021/02/17/amazon-web-services-adapter-for-sap-cloud-integration/)
