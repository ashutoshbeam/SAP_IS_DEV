import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.XmlParser
import groovy.json.JsonBuilder
import java.text.DateFormat
import java.text.SimpleDateFormat

def Message processData(Message message) {
    
    def body = message.getBody(java.lang.String)
    def messageLog = messageLogFactory.getMessageLog(message)
    def propMap = message.getProperties()
    def logMPLAttachment = propMap.get("p04_MPL_Log_Switch")

    DateFormat dateTimeFrmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
    
    String effStartDateTimeStampStr = null
    
    JsonBuilder builder = null
    LinkedHashMap root = null

    Node upsertSFPositionRequest = new Node(null, 'Position', null)
    Node positionRecordTag = null
    
    Node sacPlanList = new XmlParser().parseText(body)
    
    sacPlanList.PerPlan.each { plan ->
        //Reset Values
        builder = null
        root = null

        effStartDateTimeStampStr = "/Date(" + dateTimeFrmt.parse(plan.get("FIRST_SLICE_DATE").text()).getTime() + ")/"

        builder = new JsonBuilder()
            root = builder {
            "__metadata"({"uri"("Position")})
            "businessUnit"(plan.get("SAP_HR_BUSINESSUNIT").text())
            "changeReason"("New")
            "company"(plan.get("SAP_ALL_COMPANY_CODE").text())
            "costCenter"(plan.get("SAP_ALL_COSTCENTER").text())
            "department"(plan.get("SAP_HR_DEPARTMENT").text())
            "division"(plan.get("SAP_HR_DIVISION").text())
            "effectiveStartDate"(effStartDateTimeStampStr)
            "effectiveStatus"("A")
            "employeeClass"("1")
            "externalName_defaultValue"(plan.get("SAP_HR_JOBFUNCTION").text())
            "jobCode"(plan.get("SAP_HR_USER_JOBCLASSIFICATION").text())
            "jobLevel"("IC")
            "location"(plan.get("SAP_HR_USER_LOCATION").text())
            "multipleIncumbentsAllowed"(false)
            "payGrade"(plan.get("SAP_HR_USER_PAYGRADE").text())
            "positionCriticality"("0")
            "positionTitle"(plan.get("SAP_HR_POSITION").text())
            "regularTemporary"("R")
            "targetFTE"(plan.get("FTE_EoP").text())
            "type"("C1")
            "vacant"(true)
            "creationSource"("WORKFORCE_PLAN")
        }
    }    
    
    message.setBody(builder.toString())
    
    if(messageLog != null && logMPLAttachment == "ON+DEBUG"){
        //MPL Attachment Log Code
        messageLog.addAttachmentAsString("SF Position Insert Request JSON Payload", builder.toString(), "text/xml")
    }    
    
    return message
}    