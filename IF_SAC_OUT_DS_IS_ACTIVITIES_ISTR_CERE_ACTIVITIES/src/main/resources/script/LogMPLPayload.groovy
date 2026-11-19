import com.sap.gateway.ip.core.customdev.util.Message

def Message positionInsertResponse(Message message) {
	
	def messageLog = messageLogFactory.getMessageLog(message)
	def propertyMap = message.getProperties()
	
	String body = message.getBody(java.lang.String) as String
	def logMPLAttachment = propertyMap.get("p04_MPL_Log_Switch")
	
	//log output payload
	if(messageLog != null && logMPLAttachment.contains("ON")){
			messageLog.addAttachmentAsString("SF Position Insert Response", body, "text/xml")
	}	
	
	return message
}