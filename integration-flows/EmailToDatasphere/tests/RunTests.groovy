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
    assert !m.properties.CronacleRow.containsKey('modifiedBy')
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
test('Only requested business fields plus required ID are mapped') {
    def m=make(completed)
    extract.processData(m); jdbc.processData(m)
    assert m.properties.CronacleRow.keySet() as Set == ['ID','P_CHAIN','Status','Reason','Date','Time'] as Set
    assert !m.body.contains('modifiedBy')
    assert !m.body.contains('createdAt')
}
new File('examples').mkdirs()
def example=make(error); example.attachments.x=attachment('ErrorLog.txt',"Example failure: source file isn't available.")
extract.processData(example)
new File('examples/mapped-error.json').setText(JsonOutput.prettyPrint(example.body),'UTF-8')
jdbc.processData(example)
new File('examples/jdbc-error.xml').setText(example.body,'UTF-8')
println("${passed} tests passed. SAP tenant import and live JDBC execution still require validation.")
