package org.stockinsight.disclosure;

import java.util.Arrays;

/** 수집하는 공시 유형. OpenDART 공시유형(pblntf_ty) 중 기업의 변화를 드러내는 것만 받는다. */
public enum DisclosureType {
    /** 정기공시: 사업·반기·분기보고서. 재무 수집의 계기다. */
    PERIODIC("A"),
    /** 주요사항보고서: 증자·사채 발행·합병·영업양수도 등. */
    MAJOR_EVENT("B"),
    /** 거래소공시: 수시·공정공시, 시장조치·안내 (관리종목 지정 등). */
    EXCHANGE("I");

    private final String dartCode;

    DisclosureType(String dartCode) {
        this.dartCode = dartCode;
    }

    public String dartCode() {
        return dartCode;
    }

    public static DisclosureType fromDartCode(String dartCode) {
        return Arrays.stream(values())
                .filter(type -> type.dartCode.equals(dartCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("수집하지 않는 공시유형: " + dartCode));
    }
}
