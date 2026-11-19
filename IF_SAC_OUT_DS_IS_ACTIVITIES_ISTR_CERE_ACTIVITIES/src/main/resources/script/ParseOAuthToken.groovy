import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

def Message processData(Message message) {
    
    def body = message.getBody(String.class)
    def jsonSlurper = new JsonSlurper()
    def list = jsonSlurper.parseText(body)
    def token= list.token_type.toString() + " " + list.access_token.toString()

    message.setBody(token)
    
    return message
}
