import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import java.text.DateFormat
import java.text.SimpleDateFormat

def Message processData(Message message) {
    
    def body = message.getBody(String.class)
    def messageLog = messageLogFactory.getMessageLog(message)
    def propMap = message.getProperties()
    def logMPLAttachment = propMap.get("p04_MPL_Log_Switch")
    
    TreeMap<String,HashMap> allPlanDetailsMap = propMap.get("SAC_PLANS_AS_MAP")

    DateFormat dateFrmt = new SimpleDateFormat("yyyyMMdd")
    DateFormat dateTimeFrmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
    
    String jobClass = null
    String hrPosition = null
    String planKey = null
    String startDateStr = null
    Date firstSlicePlanDate = null
    
    TreeSet<Date> planStartDateSet = null
    HashMap<String,String> planDetailsMap = null
    TreeMap<String,TreeSet> planStartDateMap = new TreeMap<String,TreeSet>()

    JsonSlurper jsonSlurper = new JsonSlurper()
    Map lazyMap = jsonSlurper.parseText(body)
    
    ArrayList planList = lazyMap.get("value")
    
    //Iterate through all plans from SAC query response
    planList.each {
        //Reset Values
        jobClass = null
        hrPosition = null
        planKey = null
        startDateStr = null
        planStartDateSet = null
        planDetailsMap = null
    
        jobClass = it.get("SAP_HR_USER_JOBCLASSIFICATION")
        hrPosition = it.get("SAP_HR_POSITION")
        planKey = jobClass + "|" + hrPosition
    
        if(!planStartDateMap.containsKey(planKey)){
            startDateStr = it.get("Date") + "01"
            planStartDateSet = new TreeSet<Date>()
            planStartDateSet.add(dateFrmt.parse(startDateStr))
            planStartDateMap.put(planKey, planStartDateSet)
    
            planDetailsMap = new HashMap<String,String>()
            planDetailsMap.put("SAP_HR_POSITION", hrPosition)
            planDetailsMap.put("SAP_HR_USER_JOBCLASSIFICATION", jobClass)
            planDetailsMap.put("COMPOSITE_KEY", planKey)
            planDetailsMap.put("SAP_ALL_COMPANY_CODE", it.get("SAP_ALL_COMPANY_CODE"))
            planDetailsMap.put("SAP_HR_BUSINESSUNIT", it.get("SAP_HR_BUSINESSUNIT"))
            planDetailsMap.put("SAP_HR_DIVISION", it.get("SAP_HR_DIVISION"))
            planDetailsMap.put("SAP_HR_DEPARTMENT", it.get("SAP_HR_DEPARTMENT"))
            planDetailsMap.put("SAP_HR_USER_LOCATION", it.get("SAP_HR_USER_LOCATION"))
            planDetailsMap.put("SAP_ALL_COSTCENTER", it.get("SAP_ALL_COSTCENTER"))
            planDetailsMap.put("SAP_HR_USER_PAYGRADE", it.get("SAP_HR_USER_PAYGRADE"))
            planDetailsMap.put("FTE_EoP", it.get("FTE_EoP"))
            planDetailsMap.put("SAP_HR_JOBFUNCTION", it.get("SAP_HR_JOBFUNCTION"))
            allPlanDetailsMap.put(planKey,planDetailsMap)
        } else {
            startDateStr = it.get("Date") + "01"
            planStartDateSet = planStartDateMap.get(planKey)
            planStartDateSet.add(dateFrmt.parse(startDateStr))
        }
    }
    
    //Iterate through SAC Plan and Start Date Map
    planStartDateMap.each {
        //Reset Values
        planKey = null
        planStartDateSet = null
        planDetailsMap = null
        firstSlicePlanDate = null
    
        planKey = it.getKey()
        planStartDateSet = it.getValue()
        firstSlicePlanDate = planStartDateSet.first()
        planDetailsMap = allPlanDetailsMap.get(planKey)
        planDetailsMap.put("FIRST_SLICE_DATE", dateTimeFrmt.format(firstSlicePlanDate))
    }

    return message
}    