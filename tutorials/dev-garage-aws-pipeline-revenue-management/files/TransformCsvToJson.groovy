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
    // Read Sales File keeps the source file in place (Post-Processing: Keep
    // File), so it stays in daily/ for the next run.
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