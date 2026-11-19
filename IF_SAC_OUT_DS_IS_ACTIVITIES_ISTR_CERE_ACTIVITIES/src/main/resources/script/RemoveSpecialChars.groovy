import com.sap.gateway.ip.core.customdev.util.Message
import java.util.regex.Pattern

def Message processData(Message message) {
    // SAC export has four metadata lines; preserve the existing header handling.
    String body = message.getBody(String.class)
    int offset = 0
    for (int line = 0; line < 4; line++) {
        int end = body.indexOf('\n', offset)
        if (end < 0) throw new IllegalArgumentException('SAC export is missing its four metadata lines')
        offset = end + 1
    }
    String result = normalizeCsv(body.substring(offset))
    message.setBody(result)
    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addAttachmentAsString('SAC Activities Data:', result, 'text/plain')
    }
    return message
}

String normalizeCsv(String csv) {
    // Only repair the known malformed Description field, at a field boundary.
    Pattern malformed = Pattern.compile('""([A-Za-z_][A-Za-z0-9_$]*)"\\."([A-Za-z_][A-Za-z0-9_$]*)""(?=,|\\r|\\n|$)')
    Pattern partial = Pattern.compile('"([A-Za-z_][A-Za-z0-9_$]*)"\\."([A-Za-z_][A-Za-z0-9_$]*)"(?=,|\\r|\\n|$)')
    Pattern sql = Pattern.compile('"(SELECT [^\\r\\n]*;)"(?=,)')
    List<String> rows = []
    List<String> fields = []
    int pos = 0
    while (pos < csv.length()) {
        String value
        def match = malformed.matcher(csv)
        match.region(pos, csv.length())
        if (fields.size() == 3 && match.lookingAt()) {
            value = '"' + match.group(1) + '"."' + match.group(2) + '"'
            pos = match.end()
        } else if (fields.size() == 3 && partial.matcher(csv).region(pos, csv.length()).lookingAt()) {
            def part = partial.matcher(csv).region(pos, csv.length())
            part.lookingAt()
            value = part.group(1) + '"."' + part.group(2)
            pos = part.end()
        } else if (fields.size() == 3 && sql.matcher(csv).region(pos, csv.length()).lookingAt()) {
            def statement = sql.matcher(csv).region(pos, csv.length())
            statement.lookingAt()
            value = statement.group(1).replace('""', '"')
            pos = statement.end()
        } else if (csv.charAt(pos) == '"' as char) {
            pos++
            StringBuilder field = new StringBuilder()
            boolean closed = false
            while (pos < csv.length()) {
                char c = csv.charAt(pos++)
                if (c == '"' as char) {
                    if (pos < csv.length() && csv.charAt(pos) == '"' as char) {
                        field.append('"'); pos++
                    } else { closed = true; break }
                } else { field.append(c) }
            }
            if (!closed) throw new IllegalArgumentException('Unclosed CSV quote near character ' + pos)
            value = field.toString()
        } else {
            int start = pos
            while (pos < csv.length() && csv.charAt(pos) != ',' as char && csv.charAt(pos) != '\r' as char && csv.charAt(pos) != '\n' as char) {
                if (csv.charAt(pos) == '"' as char) throw new IllegalArgumentException('Unexpected CSV quote near character ' + pos)
                pos++
            }
            value = csv.substring(start, pos)
        }
        fields.add(value)
        if (pos < csv.length() && csv.charAt(pos) == ',' as char) {
            pos++
            if (pos < csv.length() && csv.charAt(pos) != '\r' as char && csv.charAt(pos) != '\n' as char) continue
            fields.add('')
        }
        if (pos < csv.length()) {
            if (csv.charAt(pos) == '\r' as char) { pos++; if (pos < csv.length() && csv.charAt(pos) == '\n' as char) pos++ }
            else if (csv.charAt(pos) == '\n' as char) pos++
            else throw new IllegalArgumentException('Unexpected character after CSV field near character ' + pos)
        }
        // Keep the original first-eight-column contract and trailing empty column.
        if (!(fields.size() == 1 && fields[0] == '')) {
            if (fields.size() < 8) throw new IllegalArgumentException('SAC CSV record has fewer than eight fields')
            rows.add(fields.take(8).collect { '"' + it.replace('"', '""') + '"' }.join(',') + ',')
        }
        fields = []
    }
    return rows.join('\n')
}
