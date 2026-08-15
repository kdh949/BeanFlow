package io.github.kdh949.beanflow.shared.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

internal class SearchTextNormalizerTest {
    /**
     * 조합형 자모로 쓴 "강남". 화면에는 완성형과 같아 보이므로 escape로 적어 둔다.
     * 눈에 보이지 않는 문자를 소스에 그대로 두면 편집기나 formatter가 조용히 바꾼다.
     */
    private val decomposedGangnam = "\u1100\u1161\u11BC\u1102\u1161\u11B7"
    private val composedGangnam = "\uAC15\uB0A8"
    private val ideographicSpace = "\u3000"
    private val nonBreakingSpace = "\u00A0"

    @Test
    fun `NFKC maps composed and decomposed Hangul to the same term`() {
        assertThat(decomposedGangnam).isNotEqualTo(composedGangnam)
        assertThat(SearchTextNormalizer.normalize(decomposedGangnam)).isEqualTo(composedGangnam)
        assertThat(SearchTextNormalizer.normalize(composedGangnam)).isEqualTo(composedGangnam)
    }

    @Test
    fun `NFKC folds full-width characters to half-width`() {
        assertThat(SearchTextNormalizer.normalize("ＳＴＡＲ")).isEqualTo("star")
        assertThat(SearchTextNormalizer.normalize("１２３")).isEqualTo("123")
        assertThat(SearchTextNormalizer.normalize("ﬁka")).isEqualTo("fika")
    }

    @Test
    fun `lowercase applies regardless of the server default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertThat(SearchTextNormalizer.normalize("ISTANBUL COFFEE")).isEqualTo("istanbul coffee")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `whitespace runs collapse to a single space and both ends are trimmed`() {
        assertThat(SearchTextNormalizer.normalize("  강남   스타벅스  ")).isEqualTo("강남 스타벅스")
        assertThat(SearchTextNormalizer.normalize("a\tb\nc\r\nd")).isEqualTo("a b c d")
    }

    @Test
    fun `NFKC turns ideographic and non-breaking spaces into collapsible whitespace`() {
        assertThat(SearchTextNormalizer.normalize("강남${ideographicSpace}스타벅스")).isEqualTo("강남 스타벅스")
        assertThat(SearchTextNormalizer.normalize("a$nonBreakingSpace$nonBreakingSpace b")).isEqualTo("a b")
    }

    @Test
    fun `blank input normalizes to an empty string without throwing`() {
        assertThat(SearchTextNormalizer.normalize("")).isEmpty()
        assertThat(SearchTextNormalizer.normalize("  $ideographicSpace\t$nonBreakingSpace ")).isEmpty()
    }

    @Test
    fun `normalize is idempotent`() {
        val once = SearchTextNormalizer.normalize("  Ｓtar${ideographicSpace}버클  ")
        assertThat(SearchTextNormalizer.normalize(once)).isEqualTo(once)
    }

    @Test
    fun `tokenize preserves input order and duplicates`() {
        assertThat(SearchTextNormalizer.tokenize("강남  스타벅스")).containsExactly("강남", "스타벅스")
        assertThat(SearchTextNormalizer.tokenize("스타벅스 강남")).containsExactly("스타벅스", "강남")
        assertThat(SearchTextNormalizer.tokenize("latte latte")).containsExactly("latte", "latte")
    }

    @Test
    fun `tokenize returns an empty list for blank input instead of a blank token`() {
        assertThat(SearchTextNormalizer.tokenize("")).isEmpty()
        assertThat(SearchTextNormalizer.tokenize("  $ideographicSpace ")).isEmpty()
    }
}
