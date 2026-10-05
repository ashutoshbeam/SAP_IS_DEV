import groovy.json.JsonOutput
import java.time.*
import java.time.format.*
import java.nio.charset.*

// The Mail adapter must provide the decoded body and attachments (not raw MIME).
def processData(def message) {
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
            eventTime = parseSent(sentValues[-1], sourceZone, (cfg.SentDatePattern ?: '').toString())
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
    String sender = emailSender(text.substring(0, alert.position as int), message.getHeaders())
    Map row = [ID:id, P_CHAIN:alert.name, Status:alert.status, Reason:reason,
               Date:local.toLocalDate().toString(), Time:timestamp,
               createdAt:timestamp, createdBy:sender, modifiedAt:timestamp, modifiedBy:sender]
    message.setProperty('CronacleRow', row)
    message.setProperty('CronacleRunId', alert.run)
    message.setProperty('CronacleTimestampSource', timeSource)
    message.setBody(JsonOutput.toJson(row))
    message.setHeader('Content-Type', 'application/json; charset=UTF-8')
    return message
}

String emailSender(String prefix, Map headers) {
    def from = prefix =~ /(?im)^\s*From\s*:\s*([^\r\n]+)/
    List values = []
    while (from.find()) values << from.group(1).trim()
    String raw = values ? values[-1] : headers.find { k,v -> k.toString().equalsIgnoreCase('From') }?.value?.toString()
    if (!raw) return null
    def address = raw =~ /[A-Za-z0-9.!#$%&'*+\/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?/
    if (!address.find()) return null
    String result = address.group()
    if (result.length() > 255) throw new IllegalArgumentException('Sender email exceeds audit field length 255')
    return result
}

String plainText(String body) {
    if (!(body =~ /(?is)<(?:html|body|div|p|br|span|table|b|strong)\b/).find()) return body
    // Text extraction for Outlook alert markup, not a general HTML renderer.
    // Uses only core string/regex APIs: no Swing, AWT, XML entities or external resources.
    String text = body.replaceAll(/(?s)<!--.*?-->/, '')
        .replaceAll(/(?is)<(head|script|style)\b[^>]*>.*?<\/\1\s*>/, '')
    // Quoted attribute values may contain '>'; do not end a tag inside them.
    def tags = ~/(?is)<\/?[A-Za-z][A-Za-z0-9:_-]*(?:"[^"]*"|'[^']*'|[^'">])*>/
    text = text.replaceAll(tags) { String tag ->
        def name = (tag =~ /(?is)^<\/?([A-Za-z][A-Za-z0-9:_-]*)/)
        name.find()
        String local = name.group(1).toLowerCase(Locale.ROOT)
        if (local in ['br','p','div','tr','table','blockquote','li','hr']) return '\n'
        if (local in ['td','th']) return ' '
        return ''
        }
    // Decode AFTER stripping markup so escaped text is never interpreted as a tag.
    Map entities = [amp:'&', lt:'<', gt:'>', quot:'"', apos:"'", nbsp:' ',
                    ensp:' ', emsp:' ', thinsp:' ', ndash:'\u2013', mdash:'\u2014',
                    lsquo:'\u2018', rsquo:'\u2019', ldquo:'\u201c', rdquo:'\u201d',
                    lrm:'', rlm:'', bull:'\u2022', hellip:'\u2026']
    text = text.replaceAll(/&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[A-Za-z][A-Za-z0-9]+);/) { String whole, String entity ->
        if (!entity.startsWith('#')) return entities.containsKey(entity) ? entities[entity] : whole
        try {
            boolean hex = entity.length() > 2 && entity.substring(1,2).equalsIgnoreCase('x')
            int cp = Integer.parseInt(entity.substring(hex ? 2 : 1), hex ? 16 : 10)
            if (!Character.isValidCodePoint(cp) || (cp >= 0xD800 && cp <= 0xDFFF) || cp == 0) return whole
            return new String(Character.toChars(cp))
        } catch (NumberFormatException ignored) { return whole }
    }
    return text.replace('\u00a0', ' ').replace('\u202f', ' ')
}

Instant parseSent(String input, ZoneId defaultZone, String customPattern = '') {
    // Outlook can use NBSP/narrow NBSP, bidi marks, abbreviated months and day-first dates.
    String value = input.replaceAll(/[\u00a0\u202f]/, ' ').replaceAll(/[\u200e\u200f\u202a-\u202e\u2066-\u2069]/, '')
        .replaceAll(/\s+/, ' ').trim()
    // Some HTML messages put adjacent forwarded header fields on the same line.
    value = value.replaceFirst(/(?i)\s+(?:To|Cc|Bcc|Subject)\s*:.*$/, '').trim()
    try { return OffsetDateTime.parse(value).toInstant() } catch (DateTimeParseException ignored) { }
    try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() } catch (DateTimeParseException ignored) { }
    ZoneId zone = defaultZone
    def offset = value =~ /(?i)\((?:UTC|GMT)\s*([+-]\d{2}:?\d{2})\)/
    if (offset.find()) {
        zone = ZoneOffset.of(offset.group(1))
        value = value.substring(0, offset.start()).trim()
    } else {
        def suffix = value =~ /(?i)\s+(?:(?:UTC|GMT)\s*)?([+-]\d{2}:?\d{2})$/
        if (suffix.find()) {
            zone = ZoneOffset.of(suffix.group(1))
            value = value.substring(0, suffix.start()).trim()
        } else if ((value =~ /(?i)\s+(UTC|GMT)$/).find()) {
            zone = ZoneOffset.UTC
            value = value.replaceFirst(/(?i)\s+(UTC|GMT)$/, '').trim()
        }
    }
    value = value.replaceFirst(/(?i)^(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)\b,?\s*/, '')
    List patterns = []
    // Numeric dates are intentionally not guessed. Configure d/M/uuuu or M/d/uuuu explicitly.
    if (customPattern) patterns << customPattern
    for (String date : ['MMMM d, uuuu','MMMM d uuuu','MMM d, uuuu','MMM d uuuu','d MMMM uuuu','d MMM uuuu','d MMMM, uuuu','d MMM, uuuu','uuuu-MM-dd']) {
        for (String time : ['h:mm:ss a','h:mm a','HH:mm:ss','HH:mm']) patterns << date + ' ' + time
    }
    for (String pattern : patterns) {
        try {
            def fmt = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
            return LocalDateTime.parse(value, fmt).atZone(zone).toInstant()
        } catch (java.time.format.DateTimeParseException ignored) { }
    }
    throw new IllegalArgumentException('Unsupported original Sent timestamp. Check the original Sent line; configure SentDatePattern for numeric/custom dates (for example d/M/uuuu h:mm a). No processing-time fallback is used.')
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
