/*
 * AttachDiagnosis.groovy
 * Exception subprocess 1 - runs after the AI Adapter's Request Reply.
 * Reads the AI response, parses the JSON diagnosis, and writes:
 *   - MPL attachment "AI Resolution" (the problem and numbered fix steps)
 *   - MPL attachment "AI Diagnosis" (human-readable report)
 *   - custom header properties AI_Status, AI_Category, AI_Confidence, AI_NeedsHuman
 *   - the message body: the resolution text followed by the original payload (personal data masked)
 *
 * Handles three cases:
 *   1. aiStatus = CONTEXT_FAILED (Capture Error failed) -> attaches a "diagnosis unavailable" note
 *   2. Response is (or contains) valid diagnosis JSON    -> formatted report
 *   3. Response is not parseable                         -> attaches raw response marked "unstructured"
 *
 * The response parser accepts either the model's text directly, or a full
 * orchestration response (final_result / orchestration_result / choices[0].message.content).
 * Never throws: error handling must not bury the original error.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

def Message processData(Message message) {
    def log = messageLogFactory.getMessageLog(message)
    // Flow ends with End Message (status Completed): flag the handled error so it stays searchable
    log?.addCustomHeaderProperty("ErrorHandled", "true")
    try {
        String status    = message.getProperty("aiStatus") ?: "OK"
        String origClass = message.getProperty("origErrorClass") ?: "unknown"
        String origMsg   = message.getProperty("origErrorMessage") ?: "-"
        String iflow     = message.getProperty("iflowName") ?: "unknown"
        String step      = message.getProperty("flowStep") ?: "unknown"

        StringBuilder r = new StringBuilder()
        r << "AI ERROR DIAGNOSIS\n"
        r << "==================\n"
        r << "iFlow       : " << iflow << "\n"
        r << "Failed step : " << step << "\n"
        r << "Error class : " << origClass << "\n"
        r << "Error       : " << origMsg << "\n\n"

        String resolutionText = null

        if (status != "OK") {
            // Case 1 - Capture Error failed, so the AI had no error to diagnose
            r << "AI diagnosis unavailable (" << status << ").\n"
            r << "Reason: " << (message.getProperty("contextError") ?: "-") << "\n"
            r << "The original error above is unaffected - investigate manually.\n"
            log?.addAttachmentAsString("AI Diagnosis", r.toString(), "text/plain")
            log?.addCustomHeaderProperty("AI_Status", status)
            resolutionText = r.toString()
        } else {
            String body    = message.getBody(String) ?: ""
            String content = extractContent(body)
            def diag       = parseJson(content)

            if (diag instanceof Map && diag.root_cause) {
                // Case 2 - structured diagnosis
                String category   = (diag.category ?: "OTHER").toString()
                String confidence = (diag.confidence ?: "LOW").toString()
                String needsHuman = (diag.needs_human == null ? "true" : diag.needs_human.toString())

                r << "Root cause  : " << diag.root_cause << "\n"
                r << "Category    : " << category << "\n"
                r << "Confidence  : " << confidence << "\n"
                r << "Needs human : " << needsHuman << "\n\n"
                r << "Suggested fix steps:\n"
                def steps = (diag.fix_steps instanceof List) ? diag.fix_steps : [diag.fix_steps ?: "-"]
                steps.eachWithIndex { s, i -> r << "  " << (i + 1) << ". " << s << "\n" }

                // Separate, resolution-only attachment for clean viewing
                StringBuilder res = new StringBuilder()
                res << "RESOLUTION\n"
                res << "==========\n\n"
                res << "Problem:\n  " << diag.root_cause << "\n\n"
                res << "What to do:\n"
                steps.eachWithIndex { s, i -> res << "  " << (i + 1) << ". " << s << "\n" }
                res << "\nCategory: " << category << "   |   Confidence: " << confidence
                res << "   |   Needs human: " << needsHuman << "\n"
                res << "\n---\nAI-generated suggestion - verify before acting.\n"
                log?.addAttachmentAsString("AI Resolution", res.toString(), "text/plain")
                resolutionText = res.toString()

                log?.addCustomHeaderProperty("AI_Status", "OK")
                log?.addCustomHeaderProperty("AI_Category", category)
                log?.addCustomHeaderProperty("AI_Confidence", confidence)
                log?.addCustomHeaderProperty("AI_NeedsHuman", needsHuman)
            } else {
                // Case 3 - model did not return the expected JSON
                r << "AI response (unstructured - did not match expected JSON):\n"
                r << (content ?: body) << "\n"
                log?.addCustomHeaderProperty("AI_Status", "UNSTRUCTURED")
            }

            r << "\n---\nAI-generated suggestion - verify before acting.\n"
            log?.addAttachmentAsString("AI Diagnosis", r.toString(), "text/plain")
            if (resolutionText == null) resolutionText = r.toString()
        }

        // Message body: the resolution, then the original payload (masked), so both show in the Monitor
        StringBuilder out = new StringBuilder(resolutionText)
        out << "\nORIGINAL PAYLOAD (personal data masked)\n"
        out << "=======================================\n"
        out << maskedPayload(message) << "\n"
        message.setBody(out.toString())

    } catch (Exception e) {
        log?.addAttachmentAsString("AI Diagnosis", "Could not build AI diagnosis report: " + e.getMessage(), "text/plain")
        log?.addCustomHeaderProperty("AI_Status", "REPORT_FAILED")
    }
    return message
}

// The masked payload that Capture Error saved inside the aiErrorContext property
String maskedPayload(Message message) {
    try {
        def ctx = new JsonSlurper().parseText(message.getProperty("aiErrorContext") as String)
        return (ctx.payloadExtract ?: "-").toString()
    } catch (ignored) {
        return "-"
    }
}

// Pull the model's text out of the response, whatever shape the adapter returns
String extractContent(String body) {
    try {
        def j = new JsonSlurper().parseText(body)
        if (j instanceof Map) {
            if (j.root_cause) return body                       // already the diagnosis itself
            for (key in ["final_result", "orchestration_result"]) {
                def c = j[key]?.choices?.getAt(0)?.message?.content
                if (c) return c.toString()
            }
            def c = j.choices?.getAt(0)?.message?.content         // plain chat-completion shape
            if (c) return c.toString()
        }
    } catch (ignored) { }
    return body                                                   // plain text response
}

// Parse JSON, tolerating ```json fences or text around the object
def parseJson(String s) {
    if (!s) return null
    String t = s.trim()
    t = t.replaceAll(/^```(?:json)?\s*/, "").replaceAll(/\s*```$/, "")
    int a = t.indexOf('{')
    int b = t.lastIndexOf('}')
    if (a >= 0 && b > a) t = t.substring(a, b + 1)
    try {
        return new JsonSlurper().parseText(t)
    } catch (ignored) {
        return null
    }
}
