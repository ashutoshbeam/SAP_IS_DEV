import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.MarkupBuilder

Message processData(Message message) {
    Map cfg = message.getProperties()
    Map row = cfg.CronacleRow as Map
    if (!row) throw new IllegalArgumentException('Run ExtractCronacleAlert before BuildJdbcPayload')
    String schema = cfg.TargetSchema?.toString()
    String table = (cfg.TargetTable ?: 'IBP_Cronacle_Status').toString()
    if (!schema) throw new IllegalArgumentException('Set TargetSchema to the writable Open SQL schema')
    String target = identifier(schema) + '.' + identifier(table)
    List keys = ['ID','P_CHAIN','Date','Time']
    List updates = ['Status','Reason']
    String columns = row.keySet().collect { identifier(it.toString()) }.join(', ')
    String source = row.collect { k,v ->
        String value = literal(v.toString())
        if (k == 'Date') value = "TO_DATE(${value}, 'YYYY-MM-DD')"
        else if (k == 'Time') value = "TO_TIMESTAMP(${value}, 'YYYY-MM-DD HH24:MI:SS.FF7')"
        value + ' AS ' + identifier(k.toString())
    }.join(', ')
    String sql = 'MERGE INTO ' + target + ' T USING (SELECT ' + source + ' FROM DUMMY) S ON (' +
        keys.collect { 'T.'+identifier(it)+' = S.'+identifier(it) }.join(' AND ') + ')\n' +
        'WHEN MATCHED THEN UPDATE SET ' + updates.collect { identifier(it)+' = S.'+identifier(it) }.join(', ') + '\n' +
        'WHEN NOT MATCHED THEN INSERT (' + columns + ') VALUES (' + row.keySet().collect { 'S.'+identifier(it.toString()) }.join(', ') + ')'
    StringWriter writer = new StringWriter()
    new MarkupBuilder(writer).root {
        Statement {
            CronacleStatus(action:'SQL_DML') { access(sql) }
        }
    }
    message.setBody(writer.toString())
    message.setHeader('Content-Type', 'application/xml; charset=UTF-8')
    // Keep email attachment content out of the receiver call.
    message.setAttachments([:])
    return message
}

String identifier(String value) {
    if (!(value ==~ /[A-Za-z_][A-Za-z0-9_#$]{0,126}/)) throw new IllegalArgumentException('Unsupported SQL identifier; use a simple schema/table name')
    return '"' + value + '"'
}

String literal(String value) {
    return "'" + value.replace("'", "''") + "'"
}
