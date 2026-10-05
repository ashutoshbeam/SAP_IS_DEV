import com.sap.gateway.ip.core.customdev.util.Message

Message processData(Message message) {
    // Set your actual writable schema here after import. No credentials belong here.
    Map defaults = [
        TargetSchema: 'REPLACE_WITH_OPEN_SQL_SCHEMA',
        TargetTable: 'IBP_Cronacle_Status',
        SourceTimeZone: 'Asia/Kolkata',
        SentDatePattern: '', // Optional explicit numeric format, e.g. dd/MM/uuuu h:mm a
        StorageTimeZone: 'UTC',
        AttachmentCharset: 'UTF-8',
        ReasonOverflowPolicy: 'FAIL'
    ]
    defaults.each { k,v -> if (!message.getProperties().containsKey(k)) message.setProperty(k,v) }
    if (message.getProperties().TargetSchema == 'REPLACE_WITH_OPEN_SQL_SCHEMA')
        throw new IllegalArgumentException('Configure TargetSchema in ConfigureMapping.groovy before deployment')
    return message
}
