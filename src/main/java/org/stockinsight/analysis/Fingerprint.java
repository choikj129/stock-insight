package org.stockinsight.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;

import org.stockinsight.signal.CompanySignal;

/**
 * 재무 쉬운 설명의 지문(D-40). 입력에 쓴 원천 식별자 집합의 해시다. 값의 해시가 아니므로(D-08) 표시 형식만 바뀌면
 * 지문은 그대로다. 정정(공시번호 변경), 신호 상태·방향·심각도·규칙 버전 변경, 데이터 한계 변경, 입력 구성 버전 변경에만 바뀐다.
 */
final class Fingerprint {

    private Fingerprint() {
    }

    static String compute(Collection<String> receiptNos, List<CompanySignal> signals, List<String> limitCodes,
            String inputBuilderVersion) {
        StringBuilder sb = new StringBuilder();
        receiptNos.stream().sorted().forEach(r -> sb.append("R:").append(r).append(';'));
        signals.stream()
                .map(s -> "N:" + s.signalType() + ":" + s.basisKey() + ":" + s.status() + ":" + s.direction() + ":"
                        + s.severity() + ":" + s.ruleVersion())
                .sorted()
                .forEach(n -> sb.append(n).append(';'));
        limitCodes.stream().sorted().forEach(c -> sb.append("L:").append(c).append(';'));
        sb.append("B:").append(inputBuilderVersion);
        return sha256(sb.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
