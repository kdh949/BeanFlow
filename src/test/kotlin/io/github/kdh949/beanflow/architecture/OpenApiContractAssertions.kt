package io.github.kdh949.beanflow.architecture

import org.assertj.core.api.Assertions.assertThat

internal fun assertOpenApiResponseStatuses(
    pathItem: String,
    vararg statuses: Int,
) {
    statuses.forEach { status ->
        assertThat(pathItem)
            .describedAs("OpenAPI response status %s", status)
            .containsPattern("(?m)^\\s*[\"']?$status[\"']?:")
    }
}

internal fun assertOpenApiTag(
    pathItem: String,
    tag: String,
) {
    assertThat(pathItem)
        .describedAs("OpenAPI tag %s", tag)
        .containsPattern("(?m)^\\s*tags:\\s*(?:\\[$tag\\]\\s*$|\\n\\s*-\\s+$tag\\s*$)")
}
