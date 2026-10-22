/*
 * CaptureError.groovy
 * Exception subprocess - first step after Error Start.
 * Captures the original error plus context, masks sensitive data, truncates,
 * and builds the JSON input for the AI adapter.
 *
 * Expects these exchange properties (set in "Set Context" content modifier):
 *   iflowName      = ${camelId}
 *   flowStep       = ValidateOrder
 *   businessObject = SalesOrder
 *
 * Output:
 *   property origErrorClass / origErrorMessage  (masked, for the attachment later)
 *   property aiErrorContext                      (compact JSON)
 *   message body                                 (pretty JSON - AI input)
 *   property aiStatus = CONTEXT_FAILED           (only if this script itself fails)
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

def Message processData(Message message) {
    try {
        // 1. The exception Cloud Integration caught
        def ex = message.getProperty("CamelExceptionCaught")

        // Use the innermost exception: Cloud Integration wraps script errors several times
        def root = ex
        while (root?.getCause() != null && root.getCause() != root) {
            root = root.getCause()
        }
        String errClass = root ? root.getClass().getName() : "unknown"
        String errMsg   = root?.getMessage() ?: ex?.getMessage() ?: "no error message"

        // 2. HTTP-type errors also carry a response body (optional, harmless if absent)
        String httpBody = ""
        if (ex != null && ex.metaClass.respondsTo(ex, "getResponseBody")) {
            httpBody = ex.getResponseBody() ?: ""
        }

        // 3. The payload as it was when the flow failed
        String payload = message.getBody(String) ?: ""

        // 4. Build the context the AI will see - masked and truncated
        def ctx = [
            iflow         : message.getProperty("iflowName") ?: "unknown",
            failedStep    : message.getProperty("flowStep") ?: "unknown",
            businessObject: message.getProperty("businessObject") ?: "",
            mplId         : message.getHeaders().get("SAP_MessageProcessingLogID") ?: "",
            errorClass    : errClass,
            errorMessage  : mask(truncate(errMsg, 4000)),
            httpResponse  : mask(truncate(httpBody, 1000)),
            payloadExtract: mask(truncate(payload, 2000))
        ]

        // 5. Save the original error first, so nothing later can overwrite it
        message.setProperty("origErrorClass", errClass)
        message.setProperty("origErrorMessage", ctx.errorMessage)

        // 6. AI input: kept as a property and set as the body
        String json = JsonOutput.toJson(ctx)
        message.setProperty("aiErrorContext", json)
        message.setBody(JsonOutput.prettyPrint(json))

    } catch (Exception e) {
        // Never throw from error handling - it would bury the original error
        message.setProperty("aiStatus", "CONTEXT_FAILED")
        message.setProperty("contextError", e.getMessage())
    }
    return message
}

String truncate(String s, int max) {
    if (!s) return ""
    return s.length() > max ? s.substring(0, max) + " ...[truncated]" : s
}

// Regex-based safety net only - NOT a full data-protection solution.
// Note: the [PHONE] pattern also masks any long number (e.g. order/PO numbers); narrow it if needed.
String mask(String s) {
    if (!s) return ""
    return s
        .replaceAll(/[\w.+-]+@[\w-]+\.[\w.]+/, "[EMAIL]")
        .replaceAll(/\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b/, "[IBAN]")
        .replaceAll(/\b(?:\d[ -]?){13,19}\b/, "[CARD]")
        .replaceAll(/(?<!\d)(?!\d{4}-\d{2}-\d{2})\+?\d[\d ()-]{8,}\d/, "[PHONE]")
        .replaceAll(/(?i)(password|pwd|secret|token|apikey|api_key)(["'\s:=>]+)[^"'\s<,&]+/, '$1$2[SECRET]')
        .replaceAll(/(?i)bearer\s+[A-Za-z0-9._~+\/=-]+/, "Bearer [SECRET]")
}