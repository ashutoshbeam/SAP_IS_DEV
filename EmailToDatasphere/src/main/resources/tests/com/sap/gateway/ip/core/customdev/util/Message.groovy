package com.sap.gateway.ip.core.customdev.util

// Local test double only. Never included in the deployable iFlow ZIP.
class Message {
    Object body = ''
    Map headers = [:]
    Map properties = [:]
    Map attachments = [:]
    Object getBody(Class type) { type == String ? body.toString() : body }
    void setProperty(String name, Object value) { properties[name] = value }
    void setHeader(String name, Object value) { headers[name] = value }
}
