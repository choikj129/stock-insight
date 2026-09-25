package org.stockinsight.ingest.dart;

/**
 * 고유번호 파일의 한 기업. 비상장사는 stockCode가 null이다 (원본은 공백 한 칸).
 */
public record DartCorpCode(String corpCode, String corpName, String stockCode, String modifyDate) {

    public boolean isListed() {
        return stockCode != null;
    }
}
