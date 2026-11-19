import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {

    def messageLog = messageLogFactory.getMessageLog(message)

    def body = message.getBody(java.lang.String)
    def propMap = message.getProperties()
    def logMPLAttachment = propMap.get("p04_MPL_Log_Switch")

    TreeMap<String,HashMap> allPlanDetailsMap = propMap.get("SAC_PLANS_AS_MAP")

    def perPlanTag = null;
    def sacPlansXMLRoot = new Node(null, 'SACPositionPlans', null)


    allPlanDetailsMap.each{ planKey, planDetailsMap ->
        perPlanTag = new Node(sacPlansXMLRoot, 'PerPlan', null)
        planDetailsMap.each{ tagName, tagValue ->
            new Node(perPlanTag,tagName,tagValue)
        }
    }

    def nodeAsText = groovy.xml.XmlUtil.serialize(sacPlansXMLRoot)

    message.setBody(nodeAsText);

    if(messageLog != null && logMPLAttachment == "ON+DEBUG"){
        //MPL Attachment Log Code
        messageLog.addAttachmentAsString("Processed SAC Plans XML Payload", nodeAsText, "text/xml")
    }

    return message
}