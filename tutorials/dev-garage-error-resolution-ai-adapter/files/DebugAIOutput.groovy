import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {
    def log = messageLogFactory.getMessageLog(message)
    String status = message.getProperty("aiStatus") ?: "OK"
    String report = "aiStatus: " + status + "\n" +
                    "aiError: " + (message.getProperty("aiError") ?: "-") + "\n\n" +
                    "AI response body:\n" + (message.getBody(String) ?: "")
    log?.addAttachmentAsString("AI output (debug)", report, "text/plain")
    return message
}