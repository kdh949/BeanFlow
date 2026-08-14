package io.github.kdh949.beanflow.shared.api

import java.text.Normalizer

/**
 * 검색 색인 기록과 질의가 공유하는 단일 정규화 함수다.
 *
 * 색인 쪽과 질의 쪽이 서로 다른 정규화를 쓰면 검색이 장애 없이 조용히 0건을 반환한다.
 * MD-2026-015가 정한 대로 두 경로가 이 객체만 사용한다.
 *
 * 변환 순서는 NFKC -> 소문자 -> 연속 공백 축약 -> trim이며 순서 자체가 계약이다.
 * NFKC를 먼저 적용해야 전각 공백(U+3000)과 non-breaking space(U+00A0)가 일반 공백으로
 * 바뀌어 뒤의 공백 축약에 걸린다. 소문자 변환은 [String.lowercase]가 locale에 의존하지
 * 않으므로 서버 default locale이 무엇이든 같은 결과를 낸다.
 */
object SearchTextNormalizer {
    /** 정규화 결과에서 토큰을 가르는 구분자. 연속된 구간은 한 칸으로 축약된다. */
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 색인 term과 검색어 양쪽에 적용하는 결정적 변환.
     *
     * 결과는 앞뒤 공백이 없고 토큰 사이가 정확히 한 칸이다. 입력이 공백뿐이면 빈 문자열이다.
     * 길이 제한과 빈 값 거부는 호출자의 검증 책임이며 여기서 하지 않는다.
     */
    fun normalize(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .replace(WHITESPACE_RUN, " ")
            .trim()

    /**
     * 정규화한 뒤 공백으로 분리한 검색 토큰. 입력 순서를 유지한다.
     *
     * ADR-070에 등록한 cursor filter hash가 토큰 배열을 입력 순서 그대로 담으므로
     * 여기서 정렬하거나 중복을 제거하지 않는다. 토큰 개수 상한은 호출자가 검증한다.
     */
    fun tokenize(text: String): List<String> {
        val normalized = normalize(text)
        return if (normalized.isEmpty()) emptyList() else normalized.split(" ")
    }
}
