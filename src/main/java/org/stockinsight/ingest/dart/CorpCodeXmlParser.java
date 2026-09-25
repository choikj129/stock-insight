package org.stockinsight.ingest.dart;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * 고유번호 파일(CORPCODE.xml)과 OpenDART XML 오류 응답을 읽는다.
 * 고유번호 파일은 수만 건이므로 스트리밍으로 읽고, 외부 엔티티는 허용하지 않는다.
 */
final class CorpCodeXmlParser {

    private final XMLInputFactory factory;

    CorpCodeXmlParser() {
        factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    List<DartCorpCode> parseCorpCodes(InputStream xml) {
        List<DartCorpCode> result = new ArrayList<>();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(xml, "UTF-8");
            String corpCode = null;
            String corpName = null;
            String stockCode = null;
            String modifyDate = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "list" -> {
                            corpCode = null;
                            corpName = null;
                            stockCode = null;
                            modifyDate = null;
                        }
                        case "corp_code" -> corpCode = blankToNull(reader.getElementText());
                        case "corp_name" -> corpName = blankToNull(reader.getElementText());
                        case "stock_code" -> stockCode = blankToNull(reader.getElementText());
                        case "modify_date" -> modifyDate = blankToNull(reader.getElementText());
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "list".equals(reader.getLocalName())) {
                    if (corpCode != null) {
                        result.add(new DartCorpCode(corpCode, corpName, stockCode, modifyDate));
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new DartApiException(DartStatus.UNEXPECTED_RESPONSE, "고유번호 파일을 읽지 못했습니다: " + e.getMessage());
        }
        return result;
    }

    /** XML 오류 응답의 status, message를 읽는다. 읽지 못하면 null 상태 코드를 돌려준다. */
    StatusMessage parseStatus(InputStream xml) {
        String status = null;
        String message = null;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(xml, "UTF-8");
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "status" -> status = blankToNull(reader.getElementText());
                        case "message" -> message = blankToNull(reader.getElementText());
                        default -> {
                        }
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            return new StatusMessage(null, null);
        }
        return new StatusMessage(status, message);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record StatusMessage(String status, String message) {
    }
}
