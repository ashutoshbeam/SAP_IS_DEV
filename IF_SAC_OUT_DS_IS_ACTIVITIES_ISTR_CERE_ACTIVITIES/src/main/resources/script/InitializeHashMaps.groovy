import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {

	def body = message.getBody(java.lang.String);
	
    //Initialize required HashMaps
    TreeMap<String,HashMap> allPlanDetailsMap = new TreeMap<String,HashMap>()
	message.setProperty("SAC_PLANS_AS_MAP", allPlanDetailsMap)

	return message
}
