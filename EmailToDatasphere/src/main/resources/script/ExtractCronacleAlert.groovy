import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import java.time.*
import java.time.format.*
import java.nio.charset.*
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator

// The Mail adapter must provide the decoded body and attachments (not raw MIME).
Message processData(Message message) {
    Map cfg = message.getProperties()
    String body = message.getBody(String) ?: ''
    if (body.length() > 2000000) throw new IllegalArgumentException('Email body exceeds 2 MB character limit')
    String text = plainText(body)
    def matcher = text =~ /(?is)\bA\s+Cronacle\s+(Chain|Process)\s+(.+?)\s*\[(\d+)\]\s+has\s+ended\s+with\s+status\s*:\s*(Completed|Error)\b/
    List alerts = []
    while (matcher.find()) alerts << [kind:matcher.group(1), name:matcher.group(2).replaceAll(/\s+/, ' ').trim(), run:matcher.group(3), status:matcher.group(4).equalsIgnoreCase('Completed') ? 'Completed' : 'Error', position:matcher.start()]
    if (alerts.size() != 1) throw new IllegalArgumentException('Expected exactly one Completed/Error Cronacle alert; found ' + alerts.size())
    Map alert = alerts[0]
    if (alert.name.length() > 220) throw new IllegalArgumentException('P_CHAIN exceeds 220 characters')

    ZoneId sourceZone = ZoneId.of((cfg.SourceTimeZone ?: 'Asia/Kolkata').toString())
    ZoneId storageZone = ZoneId.of((cfg.StorageTimeZone ?: 'UTC').toString())
    Instant eventTime
    String timeSource
    if (cfg.EventTimestampOverride) {
        eventTime = OffsetDateTime.parse(cfg.EventTimestampOverride.toString()).toInstant()
        timeSource = 'EventTimestampOverride'
    } else {
        // Use the nearest original Sent line BEFORE the one accepted alert.
        String prefix = text.substring(0, alert.position as int)
        def sent = prefix =~ /(?im)^\s*Sent\s*:\s*([^\r\n]+)/
        List sentValues = []
        while (sent.find()) sentValues << sent.group(1).trim()
        if (sentValues) {
            eventTime = parseSent(sentValues[-1], sourceZone)
            timeSource = 'Forwarded Sent'
        } else {
            def dateEntry = message.getHeaders().find { k, v -> k.toString().equalsIgnoreCase('Date') }
            def value = dateEntry?.value
            if (value instanceof Date) eventTime = value.toInstant()
            else if (value) eventTime = ZonedDateTime.parse(value.toString().trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            else throw new IllegalArgumentException('No original Sent timestamp or RFC Date header; set EventTimestampOverride explicitly')
            timeSource = 'Mail Date header'
        }
    }
    String reason = ''
    List selected = []
    if (alert.status == 'Error') {
        (message.getAttachments() ?: [:]).each { key, handler ->
            List names = [key?.toString(), handler.getName()?.toString()].findAll { it }
            String name = names.find { it.replace('\\', '/').tokenize('/')[-1] ==~ /(?i)error[ _-]*log[^\/]*\.(txt|log)/ }
            if (name) selected << [name:name, handler:handler]
        }
        selected.sort { a,b -> a.name <=> b.name }
        List logs = selected.collect { item ->
            String content = readLog(item.handler.getInputStream(), (cfg.AttachmentCharset ?: 'UTF-8').toString())
            selected.size() == 1 ? content : "--- ${item.name} ---\n${content}"
        }
        reason = logs.join('\n\n')
    }
    message.setProperty('ErrorLogFound', !selected.isEmpty())
    message.setProperty('ReasonOriginalLength', reason.length())
    message.setProperty('ReasonTruncated', false)
    if (reason.length() > 2000) {
        if ((cfg.ReasonOverflowPolicy ?: 'FAIL').toString() != 'TRUNCATE')
            throw new IllegalArgumentException('Error log exceeds Reason NVARCHAR(2000); enlarge the field or explicitly set ReasonOverflowPolicy=TRUNCATE')
        String marker = '\n[TRUNCATED]'
        int end = 2000 - marker.length()
        if (Character.isHighSurrogate(reason.charAt(end - 1))) end--
        reason = reason.substring(0, end) + marker
        message.setProperty('ReasonTruncated', true)
    }
    def local = eventTime.atZone(storageZone)
    String timestamp = local.format(DateTimeFormatter.ofPattern('uuuu-MM-dd HH:mm:ss.SSSSSSS'))
    String id = UUID.nameUUIDFromBytes(('Cronacle|' + alert.name + '|' + alert.run + '|' + eventTime.toString()).getBytes('UTF-8')).toString().replace('-', '')
    Map row = [ID:id, P_CHAIN:alert.name, Status:alert.status, Reason:reason,
               Date:local.toLocalDate().toString(), Time:timestamp]
    message.setProperty('CronacleRow', row)
    message.setProperty('CronacleRunId', alert.run)
    message.setProperty('CronacleTimestampSource', timeSource)
    message.setBody(JsonOutput.toJson(row))
    message.setHeader('Content-Type', 'application/json; charset=UTF-8')
    return message
}

String plainText(String body) {
    if (!(body =~ /(?is)<(?:html|body|div|p|br|span|table|b|strong)\b/).find()) return body
    StringBuilder out = new StringBuilder()
    int suppressed = 0
    def callback = new HTMLEditorKit.ParserCallback() {
        void handleText(char[] data, int pos) { if (suppressed == 0) out.append(data) }
        void handleStartTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet attrs, int pos) {
            if (tag.toString() in ['script','style']) suppressed++
            if (tag.toString() in ['p','div','tr','table','blockquote']) out.append('\n')
        }
        void handleEndTag(HTML.Tag tag, int pos) {
            if (tag.toString() in ['script','style']) suppressed = Math.max(0, suppressed - 1)
            if (tag.toString() in ['p','div','tr','table','blockquote']) out.append('\n')
            if (tag.toString() in ['td','th']) out.append(' ')
        }
        void handleSimpleTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet attrs, int pos) {
            if (tag == HTML.Tag.BR) out.append('\n')
        }
    }
    new ParserDelegator().parse(new StringReader(body), callback, true)
    return out.toString().replace('\u00a0', ' ')
}

Instant parseSent(String input, ZoneId defaultZone) {
    ZoneId zone = defaultZone
    def offset = input =~ /(?i)\(UTC([+-]\d{2}:\d{2})\)/
    if (offset.find()) zone = ZoneOffset.of(offset.group(1))
    String value = input.replaceFirst(/\s*\(UTC.*$/, '').trim().replaceAll(/\s+/, ' ')
    value = value.replaceFirst(/^[A-Za-z]+,\s*/, '')
    for (String pattern : ['MMMM d, uuuu h:mm:ss a', 'MMMM d, uuuu h:mm a', 'd MMMM uuuu HH:mm:ss', 'uuuu-MM-dd HH:mm:ss']) {
        try {
            def fmt = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
            return LocalDateTime.parse(value, fmt).atZone(zone).toInstant()
        } catch (java.time.format.DateTimeParseException ignored) { }
    }
    throw new IllegalArgumentException('Unsupported original Sent timestamp; use EventTimestampOverride with ISO timestamp and offset')
}

String readLog(InputStream stream, String fallbackCharset) {
    byte[] data
    stream.withCloseable { input ->
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        byte[] chunk = new byte[8192]
        int count
        while ((count = input.read(chunk)) != -1) {
            if (buffer.size() + count > 1048576) throw new IllegalArgumentException('Error-log attachment exceeds 1 MiB')
            buffer.write(chunk, 0, count)
        }
        data = buffer.toByteArray()
    }
    String charset = fallbackCharset
    int start = 0
    if (data.length >= 3 && (data[0]&255)==239 && (data[1]&255)==187 && (data[2]&255)==191) { charset='UTF-8'; start=3 }
    else if (data.length >= 2 && (data[0]&255)==255 && (data[1]&255)==254) { charset='UTF-16LE'; start=2 }
    else if (data.length >= 2 && (data[0]&255)==254 && (data[1]&255)==255) { charset='UTF-16BE'; start=2 }
    String result = Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data, start, data.length-start)).toString()
    if ((result =~ /[\x00-\x08\x0B\x0C\x0E-\x1F]/).find()) throw new IllegalArgumentException('Error log contains unsupported control characters')
    return result
}
