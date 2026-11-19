import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {
    def body = message.getBody(String.class)
    
    // Define the regex pattern for special characters
    def specialChars = /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/

    // Split the body into lines
    def lines = body.readLines()

    // Create a new list for processed lines
    def processedLines = []

    // Iterate through lines
    lines.eachWithIndex { line, index ->
        if (index < 4) {
            // Keep the first 4 lines unchanged
            //processedLines << line
        } else {
            // Remove special characters from other lines
            //processedLines << line.replaceAll(specialChars, "")
            String[] str;
            String strLine;
            str = line.split(',')
            str.eachWithIndex { strln, idx ->
            if ( idx < 8)
            {
                if ( idx == 0)
                strLine =  strln + ","
                else
                strLine = strLine + strln + ","
            }
            }
//            if (line.contains("Story"))
            processedLines << strLine
        }
    }

    // Join lines back together
    def newBody = processedLines.join("\n")

    // Set the modified body back to message
    message.setBody(newBody)
    
	def messageLog = messageLogFactory.getMessageLog(message);
	if(messageLog != null)
	{
	messageLog.addAttachmentAsString("SAC Activities Data:", newBody, "text/plain");
     }
    
    return message
}  