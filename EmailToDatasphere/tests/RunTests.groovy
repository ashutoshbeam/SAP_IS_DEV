import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

class TestAttachment {
    String name
    byte[] bytes
    InputStream getInputStream() { new ByteArrayInputStream(bytes) }
}

def shell = new GroovyShell(this.class.classLoader)
def extract = shell.parse(new File('src/main/resources/script/ExtractCronacleAlert.groovy'))
def jdbc = shell.parse(new File('src/main/resources/script/BuildJdbcPayload.groovy'))
int passed = 0
def test = { String name, Closure check -> check(); passed++; println('PASS ' + name) }
def failure = { Closure action ->
    boolean rejected = false
    try { action() } catch (Exception expected) { rejected = true }
    assert rejected : 'Expected validation failure'
}
String completed = 'Sent: Monday, October 5, 2026 10:55:23 AM (UTC+05:30) Chennai, Kolkata, Mumbai, New Delhi\nA Cronacle Chain CHAIN_EXAMPLE_SHIPMENT_FCST_GRP2 [100000001] has ended with status: Completed.'
String error = 'Sent: Thursday, October 1, 2026 9:30:44 AM (UTC+05:30) Chennai, Kolkata, Mumbai, New Delhi\nA Cronacle Chain CHAIN_EXAMPLE_CUSTOMER_MD_PRODUCT_&_SOURCE_RATIO [200000001] has ended with status: Error.'
def make = { String body -> new Message(body:body, properties:[TargetSchema:'TEST_SCHEMA']) }
def attachment = { String name, String content -> new TestAttachment(name:name, bytes:content.getBytes('UTF-8')) }

test('Completed screenshot mapping and UTC conversion') {
    def m = make(completed); extract.processData(m)
    assert m.properties.CronacleRow.P_CHAIN == 'CHAIN_EXAMPLE_SHIPMENT_FCST_GRP2'
    assert m.properties.CronacleRow.Status == 'Completed'
    assert m.properties.CronacleRow.Reason == ''
    assert m.properties.CronacleRow.Date == '2026-10-05'
    assert m.properties.CronacleRow.Time == '2026-10-05 05:25:23.0000000'
    assert m.properties.CronacleRow.ID ==~ /[a-f0-9]{32}/
    assert m.properties.CronacleRow.containsKey('modifiedBy')
}
test('HTML, ampersand, entity decoding and ErrorLog.txt') {
    def m = make(error.replace('\n','<br>').replace('&','&amp;').replace('CHAIN_EXAMPLE','<b>CHAIN_EXAMPLE').replace(' [200','</b> [311'))
    m.attachments['part1'] = attachment('ErrorLog.txt', "Can't load <source> & target\r\nSecond line")
    extract.processData(m)
    assert m.properties.CronacleRow.P_CHAIN == 'CHAIN_EXAMPLE_CUSTOMER_MD_PRODUCT_&_SOURCE_RATIO'
    assert m.properties.CronacleRow.Reason == "Can't load <source> & target\r\nSecond line"
    jdbc.processData(m)
    def xml = new XmlSlurper().parseText(m.body)
    assert xml.Statement.CronacleStatus.@action.text() == 'SQL_DML'
    String sql = xml.Statement.CronacleStatus.access.text()
    assert sql.contains("Can''t load <source> & target")
    assert sql.contains('MERGE INTO "TEST_SCHEMA"."IBP_Cronacle_Status"')
    assert sql.contains('T."Time" = S."Time"')
    assert !sql.split('WHEN MATCHED')[1].split('WHEN NOT MATCHED')[0].contains('createdAt')
    assert m.attachments.isEmpty()
}
test('Process alerts and case-insensitive .log filename') {
    def m = make(error.replace('Cronacle Chain','Cronacle Process').replace('CHAIN_EXAMPLE_CUSTOMER_MD_PRODUCT_&_SOURCE_RATIO','PROCESS_EXAMPLE_CUSTOMER_SOURCE'))
    m.attachments['ERRORLOG.LOG'] = attachment('part', 'error detail')
    m.attachments['stdout.log'] = attachment('stdout.log', 'ignore')
    extract.processData(m)
    assert m.properties.CronacleRow.Reason == 'error detail'
    assert m.properties.CronacleRow.P_CHAIN.startsWith('PROCESS_EXAMPLE_')
}
test('Stable keys across retry, distinct run IDs') {
    def a=make(completed); def b=make(completed); def c=make(completed.replace('100000001','100000002'))
    [a,b,c].each { extract.processData(it) }
    assert a.properties.CronacleRow.ID == b.properties.CronacleRow.ID
    assert a.properties.CronacleRow.ID != c.properties.CronacleRow.ID
}
test('Missing ErrorLog leaves Reason empty') {
    def m=make(error); extract.processData(m)
    assert m.properties.CronacleRow.Reason == ''
    assert !m.properties.ErrorLogFound
}
test('Completed ignores error attachments') {
    def m=make(completed); m.attachments.x=attachment('ErrorLog.txt','old error'); extract.processData(m)
    assert m.properties.CronacleRow.Reason == ''
}
test('Oversized reason fails by default, explicit truncation is marked') {
    def m=make(error); m.attachments.x=attachment('ErrorLog.txt','A'*2001)
    failure { extract.processData(m) }
    m.properties.ReasonOverflowPolicy='TRUNCATE'; extract.processData(m)
    assert m.properties.CronacleRow.Reason.length()==2000
    assert m.properties.CronacleRow.Reason.endsWith('[TRUNCATED]')
    assert m.properties.ReasonTruncated
}
test('Multiple logs joined in deterministic order') {
    def m=make(error)
    m.attachments.b=attachment('ErrorLog_B.txt','B'); m.attachments.a=attachment('ErrorLog_A.log','A')
    extract.processData(m)
    assert m.properties.CronacleRow.Reason == '--- ErrorLog_A.log ---\nA\n\n--- ErrorLog_B.txt ---\nB'
}
test('UTF-16 BOM and UTF-8 BOM decoding') {
    ['UTF-16LE','UTF-16BE','UTF-8'].each { enc ->
        byte[] bom = enc=='UTF-16LE' ? [255,254] as byte[] : enc=='UTF-16BE' ? [254,255] as byte[] : [239,187,191] as byte[]
        def buf=new ByteArrayOutputStream(); buf.write(bom); buf.write('Fehler ä'.getBytes(enc))
        def m=make(error); m.attachments.x=new TestAttachment(name:'ErrorLog.txt', bytes:buf.toByteArray()); extract.processData(m)
        assert m.properties.CronacleRow.Reason=='Fehler ä'
    }
}
test('Invalid bytes and controls rejected') {
    def m=make(error); m.attachments.x=new TestAttachment(name:'ErrorLog.txt',bytes:[255] as byte[])
    failure { extract.processData(m) }
    m.attachments.x=attachment('ErrorLog.txt','a\u0000b'); failure { extract.processData(m) }
}
test('Missing/ambiguous/unsupported alerts rejected') {
    [completed+'\n'+error, 'hello', completed.replace('Completed','Running')].each { input -> failure { extract.processData(make(input)) } }
}
test('Missing or invalid date rejected, explicit offset override supported') {
    def m=make('A Cronacle Chain A [1] has ended with status: Error.')
    failure { extract.processData(m) }
    m.properties.EventTimestampOverride='2026-10-01T09:30:44+05:30'; extract.processData(m)
    assert m.properties.CronacleRow.Time=='2026-10-01 04:00:44.0000000'
    failure { extract.processData(make(completed.replace('October 5','February 30'))) }
}
test('Direct mail uses RFC Date header') {
    def m=make(completed.split('\n')[1]); m.headers.Date='Mon, 5 Oct 2026 10:55:23 +0530'; extract.processData(m)
    assert m.properties.CronacleRow.Time=='2026-10-05 05:25:23.0000000'
}
test('Storage timezone handles date boundary') {
    def m=make(completed); m.properties.EventTimestampOverride='2026-10-05T00:10:00+05:30'; extract.processData(m)
    assert m.properties.CronacleRow.Date=='2026-10-04'
    m.setBody(completed); m.properties.StorageTimeZone='Asia/Kolkata'; extract.processData(m)
    assert m.properties.CronacleRow.Date=='2026-10-05'
}
test('Long chain and unsafe table identifiers rejected') {
    failure { extract.processData(make(completed.replace('CHAIN_EXAMPLE_SHIPMENT_FCST_GRP2','A'*221))) }
    def m=make(completed); extract.processData(m); m.properties.TargetSchema='X"; DROP TABLE X;--'
    failure { jdbc.processData(m) }
}
test('Business fields, ID and email audit fields are mapped') {
    def m=make(completed)
    extract.processData(m); jdbc.processData(m)
    assert m.properties.CronacleRow.keySet() as Set == ['ID','P_CHAIN','Status','Reason','Date','Time','createdAt','createdBy','modifiedAt','modifiedBy'] as Set
    assert m.properties.CronacleRow.createdAt == m.properties.CronacleRow.Time
    assert m.body.contains('modifiedBy')
    assert m.body.contains('createdAt')
}
test('Outlook day-first, abbreviated months, Unicode spaces and adjacent header') {
    ['Monday, 5 October 2026 10:55 AM', 'Mon 5 Oct 2026 10:55 AM',
     'October 5 2026 10:55 AM', 'Monday, October 5, 2026 10:55\u202fAM',
     '\u200eMonday, 5 October 2026 10:55 AM\u200f',
     '5 October 2026 10:55 AM To: Example recipient'].each { input ->
        assert extract.parseSent(input, java.time.ZoneId.of('Asia/Kolkata')).toString() == '2026-10-05T05:25:00Z'
    }
}
test('Additional explicit timezone and ISO formats') {
    ['5 October 2026 10:55 AM (GMT+0530) Example timezone',
     '5 Oct 2026 10:55 AM +05:30',
     '2026-10-05T10:55:00+05:30',
     'Mon, 5 Oct 2026 10:55:00 +0530'].each { input ->
        assert extract.parseSent(input, java.time.ZoneId.of('UTC')).toString() == '2026-10-05T05:25:00Z'
    }
    assert extract.parseSent('5 Oct 2026 10:55 AM UTC', java.time.ZoneId.of('Asia/Kolkata')).toString() == '2026-10-05T10:55:00Z'
}
test('Numeric dates require explicit date order; invalid dates still fail') {
    def zone=java.time.ZoneId.of('Asia/Kolkata')
    failure { extract.parseSent('05/10/2026 10:55 AM', zone) }
    assert extract.parseSent('05/10/2026 10:55 AM', zone, 'dd/MM/uuuu h:mm a').toString() == '2026-10-05T05:25:00Z'
    assert extract.parseSent('05/10/2026 10:55 AM', zone, 'MM/dd/uuuu h:mm a').toString() == '2026-05-10T05:25:00Z'
    failure { extract.parseSent('30 February 2026 10:55 AM', zone) }
}
test('Regression: Word Outlook HTML Sent 08 September 2026 10:17') {
    // Same markup/date structure as the failing email; organizational data anonymized.
    String html = '''<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns="http://www.w3.org/TR/REC-html40">
    <head><meta http-equiv="Content-Type" content="text/html; charset=utf-8"><style>p.MsoNormal {margin:0cm;}</style></head>
    <body lang="EN-IN"><div class="WordSection1"><p class="MsoNormal"><o:p>&nbsp;</o:p></p>
    <div><div><p class="MsoNormal"><b><span lang="EN-US">From:</span></b><span lang="EN-US"> Example sender &lt;sender@example.invalid&gt;<br>
    <b>Sent:</b> 08 September 2026 10:17<br><b>To:</b> Example recipient &lt;recipient@example.invalid&gt;<br>
    <b>Subject:</b> Alert: Example allocations with status Completed<o:p></o:p></span></p></div></div>
    <p class="MsoNormal"><span lang="EN-US">A Cronacle Chain <b>CHAIN_EXAMPLE_ALLOCATIONS</b> [100000003] has ended with status: <b><span style="color:#8F0038">Completed</span></b>.<o:p></o:p></span></p></div></body></html>'''
    def m=make(html); extract.processData(m)
    assert m.properties.CronacleRow.P_CHAIN=='CHAIN_EXAMPLE_ALLOCATIONS'
    assert m.properties.CronacleRow.Status=='Completed'
    assert m.properties.CronacleRow.Date=='2026-09-08'
    assert m.properties.CronacleRow.Time=='2026-09-08 04:47:00.0000000'
    assert m.properties.CronacleTimestampSource=='Forwarded Sent'
}
test('HTML extraction without desktop APIs: entities, comments and quoted attributes') {
    assert extract.plainText('<p title="a > b">CHAIN_<b>A</b>_&amp;_B &#38; &#x26; &lt;literal&gt;</p>').trim() == 'CHAIN_A_&_B & & <literal>'
    assert extract.plainText('<html><head><style>hidden</style></head><body><!-- hidden --><script>hidden</script><p>visible</p></body></html>').trim() == 'visible'
    assert extract.plainText('<p>&amp;lt; &#x1F600; &#999999999999; &unknown;</p>').trim() == '&lt; ' + new String(Character.toChars(0x1F600)) + ' &#999999999999; &unknown;'
    assert extract.plainText('plain <source> & text') == 'plain <source> & text'
    String source = new File('src/main/resources/script/ExtractCronacleAlert.groovy').getText('UTF-8')
    assert !source.contains('javax.swing') && !source.contains('sun.awt') && !source.contains('ParserDelegator')
}
test('Original sender is used for forwarded email audit fields') {
    def m=make('From: Original <original@example.invalid>\n'+completed)
    m.headers.From='forwarder@example.invalid'
    extract.processData(m)
    assert m.properties.CronacleRow.createdBy=='original@example.invalid'
    assert m.properties.CronacleRow.modifiedBy=='original@example.invalid'
    assert m.properties.CronacleRow.createdAt=='2026-10-05 05:25:23.0000000'
    jdbc.processData(m)
    String sql=new XmlSlurper().parseText(m.body).Statement.CronacleStatus.access.text()
    String update=sql.split('WHEN MATCHED')[1].split('WHEN NOT MATCHED')[0]
    assert update.contains('modifiedBy') && !update.contains('createdBy') && !update.contains('createdAt')
}
test('Direct email audit sender and missing sender') {
    def m=make(completed); m.headers.From='Direct <direct@example.invalid>'; extract.processData(m)
    assert m.properties.CronacleRow.createdBy=='direct@example.invalid'
    def missing=make(completed); extract.processData(missing); jdbc.processData(missing)
    assert missing.properties.CronacleRow.createdBy==null
    assert missing.body.contains('CAST(NULL AS NVARCHAR(255))')
}
test('All entry points accept a message unrelated to the legacy SAP class') {
    def m=new IndependentMessage(body:completed, properties:[TargetSchema:'TEST_SCHEMA'], headers:[From:'test@example.invalid'])
    shell.parse(new File('src/main/resources/script/ConfigureMapping.groovy')).processData(m)
    extract.processData(m); jdbc.processData(m)
    assert m.body.contains('MERGE INTO')
}
new File('examples').mkdirs()
def example=make(error); example.attachments.x=attachment('ErrorLog.txt',"Example failure: source file isn't available.")
extract.processData(example)
new File('examples/mapped-error.json').setText(JsonOutput.prettyPrint(example.body),'UTF-8')
jdbc.processData(example)
new File('examples/jdbc-error.xml').setText(example.body,'UTF-8')
println("${passed} tests passed. SAP tenant import and live JDBC execution still require validation.")

// Deliberately does not extend the legacy Message test double.
class IndependentMessage {
    Object body
    Map properties=[:]
    Map headers=[:]
    Map attachments=[:]
    Object getBody(Class type) { body.toString() }
    void setProperty(String name,Object value) { properties[name]=value }
    void setHeader(String name,Object value) { headers[name]=value }
}
