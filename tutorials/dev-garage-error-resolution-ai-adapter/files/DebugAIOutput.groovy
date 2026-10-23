import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {
    def log = messageLogFactory.getMessageLog(message)
    String report = "AI response body:\n" + (message.getBody(String) ?: "")
    log?.addAttachmentAsString("AI output (debug)", report, "text/plain")
    return message
}
